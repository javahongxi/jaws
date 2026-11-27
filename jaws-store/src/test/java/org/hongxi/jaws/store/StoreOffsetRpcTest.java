package org.hongxi.jaws.store;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.store.client.StoreConsumer;
import org.hongxi.jaws.store.client.StoreProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for broker-side offsets over the real jaws protocol: the query, tail
 * and commit round trips including the increaseOnly rule, the pump committing
 * what it consumed, and the two rules a consumer's position rests on — a group
 * with a stored offset resumes there, a group the broker has never seen starts at
 * the queue tail instead of replaying the backlog.
 */
class StoreOffsetRpcTest {

    @TempDir
    Path tempDir;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ReferenceConfig<StoreService> storeReference(String directUrl) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");

        ReferenceConfig<StoreService> reference = new ReferenceConfig<>();
        reference.setInterface(StoreService.class);
        reference.setApplication("jaws-store-offset-client");
        reference.setModule("store");
        reference.setGroup(StoreBroker.GROUP);
        reference.setVersion(StoreBroker.VERSION);
        reference.setRequestTimeout(3000);
        reference.setDirectUrl(directUrl);
        reference.setProtocol(protocolConfig);
        return reference;
    }

    private StoreConfig config(String name, String brokerName) {
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve(name).toString());
        config.setBrokerName(brokerName);
        config.setMappedFileSizeCommitLog(4096);
        config.setMappedFileSizeConsumeQueue(100 * ConsumeQueue.CQ_STORE_UNIT_SIZE);
        config.setMaxMessageSize(4096);
        config.setHoldScanIntervalMillis(50);
        return config;
    }

    /**
     * Offset commits are fire-and-forget, so give one a window to land and
     * report the last observed value: the caller's assertion then names both
     * sides of the mismatch instead of an opaque timeout.
     */
    private static long awaitBrokerOffset(StoreBroker broker, String group, String topic, int queueId,
                                          long expected) throws InterruptedException {
        long last = -1L;
        for (int i = 0; i < 50; i++) {
            Long offset = broker.getOffsetStore().queryOffset(group, topic, queueId);
            if (offset != null) {
                last = offset;
                if (offset == expected) {
                    return offset;
                }
            }
            Thread.sleep(100);
        }
        return last;
    }

    /**
     * Reput builds the ConsumeQueue index after the durability barrier, so a
     * tail read right after a send may still lag. Wait for it to catch up: the
     * bootstrap decision under test is the tail the broker actually reports.
     */
    private static void awaitTail(StoreBroker broker, String topic, int queueId, long expected)
            throws InterruptedException {
        for (int i = 0; i < 50 && broker.getMessageStore().getMaxOffsetInQueue(topic, queueId) < expected; i++) {
            Thread.sleep(100);
        }
        assertEquals(expected, broker.getMessageStore().getMaxOffsetInQueue(topic, queueId));
    }

    @Test
    void commitAndQueryRoundTripOverTheWire() throws Exception {
        StoreBroker broker = new StoreBroker(config("rpc", "rpc-broker"), freePort());
        broker.start();
        ReferenceConfig<StoreService> ref = storeReference("127.0.0.1:" + broker.getPort());
        StoreService service = ref.getRef();
        try {
            // Absence is an explicit answer, never a silent 0.
            OffsetResult absent = service.queryOffsetAsync("g1", "demo", 0).get(5, TimeUnit.SECONDS);
            assertFalse(absent.isFound(), "an untouched group must read as not found");
            assertEquals(-1L, absent.getOffset());

            assertEquals(5L, service.commitOffsetAsync("g1", "demo", 0, 5L, true)
                    .get(5, TimeUnit.SECONDS).getOffset());
            assertEquals(5L, service.queryOffsetAsync("g1", "demo", 0).get(5, TimeUnit.SECONDS).getOffset());

            // increaseOnly: the reply reports where the group actually stands.
            OffsetResult lost = service.commitOffsetAsync("g1", "demo", 0, 3L, true).get(5, TimeUnit.SECONDS);
            assertTrue(lost.isFound());
            assertEquals(5L, lost.getOffset(), "a lagging commit must not rewind the group");

            // A deliberate reset may go backwards.
            assertEquals(2L, service.commitOffsetAsync("g1", "demo", 0, 2L, false)
                    .get(5, TimeUnit.SECONDS).getOffset());

            // Groups are isolated, and a stored 0 stays distinguishable from absence.
            assertFalse(service.queryOffsetAsync("g2", "demo", 0).get(5, TimeUnit.SECONDS).isFound());
            assertEquals(0L, service.commitOffsetAsync("g2", "demo", 0, 0L, true)
                    .get(5, TimeUnit.SECONDS).getOffset());
            assertTrue(service.queryOffsetAsync("g2", "demo", 0).get(5, TimeUnit.SECONDS).isFound());
        } finally {
            ref.destroy();
            broker.shutdown();
        }
    }

    /**
     * The tail is a queue property, not a group one: always answerable, never
     * moved by a commit, and the answer a brand new group starts from.
     */
    @Test
    void maxOffsetIsQueueScopedAndAlwaysAnswerable() throws Exception {
        StoreBroker broker = new StoreBroker(config("tail", "tail-broker"), freePort());
        broker.start();
        String url = "127.0.0.1:" + broker.getPort();
        ReferenceConfig<StoreService> ref = storeReference(url);
        StoreService service = ref.getRef();
        StoreProducer producer = new StoreProducer(url);
        try {
            assertEquals(0L, service.maxOffsetAsync("never-written", 0).get(5, TimeUnit.SECONDS),
                    "an empty queue has a tail of 0 rather than no answer");

            for (int i = 0; i < 3; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("tail", 0, "t-" + i)).getStatus());
            }
            awaitTail(broker, "tail", 0, 3L);
            assertEquals(3L, service.maxOffsetAsync("tail", 0).get(5, TimeUnit.SECONDS));

            // A commit records a group's position; it does not move the log.
            service.commitOffsetAsync("g1", "tail", 0, 99L, true).get(5, TimeUnit.SECONDS);
            assertEquals(3L, service.maxOffsetAsync("tail", 0).get(5, TimeUnit.SECONDS));
            assertEquals(0L, service.maxOffsetAsync("tail", 1).get(5, TimeUnit.SECONDS));
        } finally {
            ref.destroy();
            producer.shutdown();
            broker.shutdown();
        }
    }

    @Test
    void pumpCommitsProgressToTheBroker() throws Exception {
        StoreBroker broker = new StoreBroker(config("pump", "pump-broker"), freePort());
        broker.start();
        String url = "127.0.0.1:" + broker.getPort();
        StoreProducer producer = new StoreProducer(url);
        StoreConsumer consumer = new StoreConsumer(url, "pump-group");
        try {
            // Consume first: the pumps suspend on an empty queue, so everything
            // sent below is part of this group's position rather than a backlog
            // it starts past.
            CountDownLatch latch = new CountDownLatch(6);
            consumer.subscribe("pump", 0, messages -> messages.forEach(m -> latch.countDown()));
            consumer.start();
            for (int i = 0; i < 6; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("pump", 0, "m-" + i)).getStatus());
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "all six messages should be delivered");
            assertEquals(6L, awaitBrokerOffset(broker, "pump-group", "pump", 0, 6L));
        } finally {
            consumer.shutdown();
            producer.shutdown();
            broker.shutdown();
        }
    }

    /**
     * The point of server-side authority: nothing on this host remembers where the
     * group was, yet the broker answers, so the second instance picks the pump up
     * at five and replays nothing.
     */
    @Test
    void secondInstanceResumesFromTheBroker() throws Exception {
        StoreBroker broker = new StoreBroker(config("resume", "resume-broker"), freePort());
        broker.start();
        String url = "127.0.0.1:" + broker.getPort();
        StoreProducer producer = new StoreProducer(url);
        StoreConsumer consumer = new StoreConsumer(url, "resume-group");
        try {
            CountDownLatch latch = new CountDownLatch(5);
            consumer.subscribe("resume", 0, messages -> messages.forEach(m -> latch.countDown()));
            consumer.start();
            for (int i = 0; i < 5; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("resume", 0, "r-" + i)).getStatus());
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "first instance should consume all five");
            assertEquals(5L, awaitBrokerOffset(broker, "resume-group", "resume", 0, 5L));
            consumer.shutdown();

            // A brand new instance: without the broker record this would be a new
            // group, start at the tail and never see the five again.
            StoreConsumer rebuilt = new StoreConsumer(url, "resume-group");
            AtomicInteger redelivered = new AtomicInteger();
            rebuilt.subscribe("resume", 0, messages -> redelivered.addAndGet(messages.size()));
            rebuilt.start();
            Thread.sleep(1500);
            assertEquals(0, redelivered.get(), "a rebuilt instance must resume at 5, not replay");
            rebuilt.shutdown();
        } finally {
            consumer.shutdown();
            producer.shutdown();
            broker.shutdown();
        }
    }

    /**
     * The bootstrap policy on its own: a group the broker has never committed for
     * starts at the tail, so a backlog waiting there is skipped and only what
     * arrives after the attach is delivered.
     */
    @Test
    void newGroupStartsAtTheQueueTail() throws Exception {
        StoreBroker broker = new StoreBroker(config("bootstrap", "bootstrap-broker"), freePort());
        broker.start();
        String url = "127.0.0.1:" + broker.getPort();
        StoreProducer producer = new StoreProducer(url);
        for (int i = 0; i < 4; i++) {
            assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("boot", 0, "old-" + i)).getStatus());
        }
        awaitTail(broker, "boot", 0, 4L);
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        StoreConsumer consumer = new StoreConsumer(url, "boot-group");
        try {
            consumer.subscribe("boot", 0, messages -> {
                delivered.addAndGet(messages.size());
                messages.forEach(m -> latch.countDown());
            });
            consumer.start();
            for (int i = 0; i < 2; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("boot", 0, "new-" + i)).getStatus());
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS), "messages after the attach must arrive");
            Thread.sleep(500);
            assertEquals(2, delivered.get(), "the backlog the group never claimed stays behind the tail");
            // The tail it started from became a recorded position on the first commit.
            assertEquals(6L, awaitBrokerOffset(broker, "boot-group", "boot", 0, 6L));
        } finally {
            consumer.shutdown();
            producer.shutdown();
            broker.shutdown();
        }
    }

    /**
     * The broker snapshot is what survives a broker restart: offsets reload from
     * storePath/config/consumerOffsets.properties and answer over the wire.
     */
    @Test
    void offsetsSurviveBrokerRestart() throws Exception {
        StoreConfig config = config("restart", "restart-broker");
        StoreBroker first = new StoreBroker(config, freePort());
        first.start();
        ReferenceConfig<StoreService> ref = storeReference("127.0.0.1:" + first.getPort());
        ref.getRef().commitOffsetAsync("g1", "demo", 0, 17L, true).get(5, TimeUnit.SECONDS);
        ref.destroy();
        first.shutdown();

        int port = freePort();
        StoreBroker second = new StoreBroker(config, port);
        second.start();
        ReferenceConfig<StoreService> reopened = storeReference("127.0.0.1:" + port);
        try {
            OffsetResult result = reopened.getRef().queryOffsetAsync("g1", "demo", 0).get(5, TimeUnit.SECONDS);
            assertTrue(result.isFound(), "the snapshot must reload after a restart");
            assertEquals(17L, result.getOffset());
        } finally {
            reopened.destroy();
            second.shutdown();
        }
    }

    /**
     * Multi-master: the same topic@queueId on two brokers is two independent
     * records, each held by the broker that owns the queue.
     */
    @Test
    void perBrokerRecordsAreIndependent() throws Exception {
        StoreBroker brokerA = new StoreBroker(config("pair-a", "pair-a"), freePort());
        StoreBroker brokerB = new StoreBroker(config("pair-b", "pair-b"), freePort());
        brokerA.start();
        brokerB.start();
        ReferenceConfig<StoreService> refA = storeReference("127.0.0.1:" + brokerA.getPort());
        ReferenceConfig<StoreService> refB = storeReference("127.0.0.1:" + brokerB.getPort());
        try {
            refA.getRef().commitOffsetAsync("shared", "same", 0, 4L, true).get(5, TimeUnit.SECONDS);

            assertEquals(4L, refA.getRef().queryOffsetAsync("shared", "same", 0).get(5, TimeUnit.SECONDS).getOffset());
            assertFalse(refB.getRef().queryOffsetAsync("shared", "same", 0).get(5, TimeUnit.SECONDS).isFound(),
                    "a commit to one broker must not appear on another");
            assertEquals(4L, brokerA.getOffsetStore().queryOffset("shared", "same", 0));
            assertEquals(0, brokerB.getOffsetStore().size(), "the idle broker tracks nothing");
        } finally {
            refA.destroy();
            refB.destroy();
            brokerA.shutdown();
            brokerB.shutdown();
        }
    }
}
