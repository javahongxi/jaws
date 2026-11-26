package org.hongxi.jaws.store;

import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.store.client.StoreConsumer;
import org.hongxi.jaws.store.client.StoreProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-master sharding semantics: stable broker identity, per-broker offset
 * isolation, registry discovery with broker failover, and consistent-hash
 * queue pinning for ordered sends. No rebalance by design — one consumer
 * instance consumes every queue of every broker.
 */
class StoreMultiBrokerTest {

    @TempDir
    Path tempDir;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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

    private static RegistryConfig localRegistry(int port) {
        RegistryConfig registry = new RegistryConfig();
        registry.setProtocol("local");
        registry.setId("storeTestRegistry");
        registry.setAddress("127.0.0.1:" + port);
        registry.setPort(port);
        return registry;
    }

    @Test
    void brokerIdentityPersistedAndEnforced() throws Exception {
        StoreConfig config = config("identity", "alpha");
        MessageStore store = new MessageStore(config);
        store.start();
        Path identityFile = tempDir.resolve("identity/broker.identity");
        assertTrue(Files.exists(identityFile));
        assertEquals("alpha", Files.readString(identityFile).trim());
        store.shutdown();

        StoreConfig renamed = config("identity", "beta");
        MessageStore mismatched = new MessageStore(renamed);
        assertThrows(IOException.class, mismatched::start);

        MessageStore sameName = new MessageStore(config("identity", "alpha"));
        sameName.start();
        sameName.shutdown();
    }

    @Test
    void offsetsIsolatedAcrossBrokers() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-iso").toString());
        StoreBroker brokerA = new StoreBroker(config("iso-a", "broker-a"), freePort());
        StoreBroker brokerB = new StoreBroker(config("iso-b", "broker-b"), freePort());
        brokerA.start();
        brokerB.start();
        String urlA = "127.0.0.1:" + brokerA.getPort();
        String urlB = "127.0.0.1:" + brokerB.getPort();
        StoreProducer producerA = new StoreProducer(urlA);
        StoreProducer producerB = new StoreProducer(urlB);
        StoreConsumer consumer = new StoreConsumer(urlA + "," + urlB, "iso-group");
        try {
            for (int i = 0; i < 5; i++) {
                producerA.sendSync(StoreMessage.of("iso", 0, "a-" + i));
                producerB.sendSync(StoreMessage.of("iso", 0, "b-" + i));
            }
            AtomicInteger received = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(10);
            consumer.subscribe("iso", 0, messages -> {
                received.addAndGet(messages.size());
                messages.forEach(m -> latch.countDown());
            });
            consumer.start();
            assertTrue(latch.await(10, TimeUnit.SECONDS), "expected 10 messages from both brokers");
            assertEquals(10, received.get());
            consumer.shutdown();

            Properties offsets = new Properties();
            Path offsetFile = tempDir.resolve("client-iso/iso-group/offsets.properties");
            try (InputStream in = Files.newInputStream(offsetFile)) {
                offsets.load(in);
            }
            assertEquals("5", offsets.getProperty("broker-a@iso@0"));
            assertEquals("5", offsets.getProperty("broker-b@iso@0"));

            // restart with the same group: both per-broker offsets resume at 5,
            // nothing is re-consumed
            StoreConsumer resumed = new StoreConsumer(urlA + "," + urlB, "iso-group");
            AtomicInteger reReceived = new AtomicInteger();
            resumed.subscribe("iso", 0, messages -> reReceived.addAndGet(messages.size()));
            resumed.start();
            Thread.sleep(1000);
            assertEquals(0, reReceived.get());
            resumed.shutdown();
        } finally {
            consumer.shutdown();
            producerA.shutdown();
            producerB.shutdown();
            brokerA.shutdown();
            brokerB.shutdown();
            restoreClientPath(previous);
        }
    }

    @Test
    void registryDiscoveryAndBrokerFailover() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-reg").toString());
        RegistryConfig registry = localRegistry(freePort());
        StoreBroker brokerA = new StoreBroker(config("reg-a", "reg-a"), freePort(), "fastjson2", List.of(registry));
        StoreBroker brokerB = new StoreBroker(config("reg-b", "reg-b"), freePort(), "fastjson2", List.of(registry));
        brokerA.start();
        brokerB.start();
        StoreProducer producer = new StoreProducer(List.of(registry));
        StoreConsumer consumer = new StoreConsumer(List.of(registry), "reg-group");
        try {
            AtomicInteger received = new AtomicInteger();
            CountDownLatch firstBatch = new CountDownLatch(10);
            CountDownLatch all = new CountDownLatch(20);
            consumer.subscribe("reg", 0, messages -> {
                received.addAndGet(messages.size());
                messages.forEach(m -> {
                    firstBatch.countDown();
                    all.countDown();
                });
            });
            consumer.start();
            for (int i = 0; i < 10; i++) {
                producer.sendSync(StoreMessage.of("reg", 0, "m-" + i));
            }
            assertTrue(firstBatch.await(15, TimeUnit.SECONDS), "discovery should attach both brokers");

            // kill one broker; the consumer must detach it and keep consuming
            // from the survivor (offsets of the dead broker stay untouched)
            brokerB.shutdown();
            StoreProducer directA = new StoreProducer("127.0.0.1:" + brokerA.getPort());
            try {
                Thread.sleep(6000);
                for (int i = 10; i < 20; i++) {
                    directA.sendSync(StoreMessage.of("reg", 0, "m-" + i));
                }
                assertTrue(all.await(15, TimeUnit.SECONDS), "survivor broker should keep delivering");
                assertEquals(20, received.get());
            } finally {
                directA.shutdown();
            }
        } finally {
            consumer.shutdown();
            producer.shutdown();
            brokerA.shutdown();
            restoreClientPath(previous);
        }
    }

    @Test
    void consistentHashPinsQueueToOneBroker() throws Exception {
        RegistryConfig registry = localRegistry(freePort());
        StoreBroker brokerA = new StoreBroker(config("pin-a", "pin-a"), freePort(), "fastjson2", List.of(registry));
        StoreBroker brokerB = new StoreBroker(config("pin-b", "pin-b"), freePort(), "fastjson2", List.of(registry));
        brokerA.start();
        brokerB.start();
        StoreProducer producer = new StoreProducer(List.of(registry), "fastjson2", 3000,
                StoreProducer.DEFAULT_MAX_INFLIGHT, "consistentHash");
        try {
            for (int i = 0; i < 20; i++) {
                PutResult result = producer.sendSync(StoreMessage.of("pin", 0, "p-" + i));
                assertTrue(result.isOk(), "put rejected: " + result.getStatus());
            }
            // reput builds the ConsumeQueue index asynchronously after the
            // durability barrier, so poll until the index catches up
            int countA = 0;
            int countB = 0;
            for (int i = 0; i < 50 && countA + countB < 20; i++) {
                Thread.sleep(100);
                countA = pulledCount(brokerA, "pin");
                countB = pulledCount(brokerB, "pin");
            }
            assertEquals(20, countA + countB);
            assertTrue(countA == 20 || countB == 20,
                    "one queue must stay on one broker, got A=" + countA + " B=" + countB);
        } finally {
            producer.shutdown();
            brokerA.shutdown();
            brokerB.shutdown();
        }
    }

    private static int pulledCount(StoreBroker broker, String topic) throws Exception {
        PullResult result = broker.getMessageStore().getMessageAsync(topic, 0, 0, 100, 0)
                .get(5, TimeUnit.SECONDS);
        return result.getStatus() == PullStatus.FOUND ? result.getMessages().size() : 0;
    }

    private static void restoreClientPath(String previous) {
        if (previous == null) {
            System.clearProperty("jaws.store.client.path");
        } else {
            System.setProperty("jaws.store.client.path", previous);
        }
    }
}