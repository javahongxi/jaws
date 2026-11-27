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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for broker-side offsets over the real jaws protocol: the query and
 * commit round trip including the increaseOnly rule, the pump committing what it
 * consumed, and the behaviour this change exists for — a consumer whose local
 * offset file is gone still resumes where the group left off.
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

    @Test
    void pumpCommitsProgressToTheBroker() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-pump").toString());
        StoreBroker broker = new StoreBroker(config("pump", "pump-broker"), freePort());
        broker.start();
        String url = "127.0.0.1:" + broker.getPort();
        StoreProducer producer = new StoreProducer(url);
        StoreConsumer consumer = new StoreConsumer(url, "pump-group");
        try {
            for (int i = 0; i < 6; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("pump", 0, "m-" + i)).getStatus());
            }
            CountDownLatch latch = new CountDownLatch(6);
            consumer.subscribe("pump", 0, messages -> messages.forEach(m -> latch.countDown()));
            consumer.start();
            assertTrue(latch.await(10, TimeUnit.SECONDS), "all six messages should be delivered");
            assertEquals(6L, awaitBrokerOffset(broker, "pump-group", "pump", 0, 6L));
        } finally {
            consumer.shutdown();
            producer.shutdown();
            broker.shutdown();
            restoreClientPath(previous);
        }
    }

    /**
     * The point of the whole change: with the client's offset file gone — a
     * rebuilt container, a different host — the broker still answers where the
     * group is, so nothing is replayed.
     */
    @Test
    void freshInstanceResumesFromBrokerAfterLocalFileLost() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-first").toString());
        StoreBroker broker = new StoreBroker(config("resume", "resume-broker"), freePort());
        broker.start();
        String url = "127.0.0.1:" + broker.getPort();
        StoreProducer producer = new StoreProducer(url);
        StoreConsumer consumer = new StoreConsumer(url, "resume-group");
        try {
            for (int i = 0; i < 5; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("resume", 0, "r-" + i)).getStatus());
            }
            CountDownLatch latch = new CountDownLatch(5);
            consumer.subscribe("resume", 0, messages -> messages.forEach(m -> latch.countDown()));
            consumer.start();
            assertTrue(latch.await(10, TimeUnit.SECONDS), "first instance should consume all five");
            assertEquals(5L, awaitBrokerOffset(broker, "resume-group", "resume", 0, 5L));
            consumer.shutdown();
            assertTrue(Files.exists(tempDir.resolve("client-first/resume-group/offsets.properties")),
                    "the local cache is still written while the group runs");

            // A clean local directory: without the broker record this instance
            // would start from 0 and re-deliver all five messages.
            System.setProperty("jaws.store.client.path", tempDir.resolve("client-lost").toString());
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
            restoreClientPath(previous);
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

    /**
     * With remote tracking switched off the consumer behaves exactly as it did
     * before: progress stays in the local file and the broker records nothing.
     */
    @Test
    void remoteOffsetsCanBeDisabled() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-local").toString());
        StoreBroker broker = new StoreBroker(config("local-only", "local-only"), freePort());
        broker.start();
        String url = "127.0.0.1:" + broker.getPort();
        StoreProducer producer = new StoreProducer(url);
        StoreConsumer consumer = new StoreConsumer(url, "local-group");
        try {
            for (int i = 0; i < 3; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("local", 0, "l-" + i)).getStatus());
            }
            CountDownLatch latch = new CountDownLatch(3);
            consumer.setRemoteOffsets(false);
            consumer.subscribe("local", 0, messages -> messages.forEach(m -> latch.countDown()));
            consumer.start();
            assertTrue(latch.await(10, TimeUnit.SECONDS), "delivery must not depend on offset RPCs");
            assertEquals(3, received(broker, "local", latch));
            assertEquals(0, broker.getOffsetStore().size(), "no commit should reach the broker");
        } finally {
            consumer.shutdown();
            producer.shutdown();
            broker.shutdown();
            restoreClientPath(previous);
        }
    }

    /** The latch reaching zero proves all three bodies arrived; report its count. */
    private static int received(StoreBroker broker, String topic, CountDownLatch latch) throws Exception {
        PullResult result = broker.getMessageStore().getMessageAsync(topic, 0, 0, 32, 0).get(5, TimeUnit.SECONDS);
        assertEquals(0L, latch.getCount(), "listener should have been released for every message");
        return result.getMessages().size();
    }

    private static void restoreClientPath(String previous) {
        if (previous == null) {
            System.clearProperty("jaws.store.client.path");
        } else {
            System.setProperty("jaws.store.client.path", previous);
        }
    }
}
