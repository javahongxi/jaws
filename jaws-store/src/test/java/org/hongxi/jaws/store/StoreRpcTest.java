package org.hongxi.jaws.store;

import org.hongxi.jaws.store.client.StoreConsumer;
import org.hongxi.jaws.store.client.StoreProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test over the real jaws protocol: StoreBroker + StoreProducer
 * + StoreConsumer on a local Netty port. The consumer starts before any
 * message exists, so its first pulls exercise long polling over RPC.
 */
class StoreRpcTest {

    @TempDir
    Path tempDir;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void produceConsumeOverJawsProtocol() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client").toString());
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve("store").toString());
        config.setHoldScanIntervalMillis(200);

        int port = freePort();
        StoreBroker broker = new StoreBroker(config, port);
        broker.start();
        String directUrl = "127.0.0.1:" + port;
        StoreProducer producer = new StoreProducer(directUrl);
        StoreConsumer consumer = new StoreConsumer(directUrl, "rpc-test-group");
        try {
            int total = 50;
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(total);
            consumer.subscribe("rpc-demo", 0, messages -> {
                for (MessageView view : messages) {
                    received.add(view.bodyAsString());
                    latch.countDown();
                }
            });
            consumer.start();

            for (int i = 0; i < total; i++) {
                PutResult result = producer.sendSync(StoreMessage.of("rpc-demo", 0, "msg-" + i));
                assertEquals(PutStatus.PUT_OK, result.getStatus());
            }
            assertTrue(latch.await(15, TimeUnit.SECONDS), "consumer should receive all messages");
            assertEquals(total, received.size());
            assertEquals("msg-0", received.get(0));
        } finally {
            consumer.shutdown();
            producer.shutdown();
            broker.shutdown();
            if (previous == null) {
                System.clearProperty("jaws.store.client.path");
            } else {
                System.setProperty("jaws.store.client.path", previous);
            }
        }
    }

    /**
     * Short polling (suspendTimeoutMillis <= 0): the broker never holds an empty
     * read, so it returns instantly. The consumer must space out the next pump by
     * the configured interval instead of hammering the broker. A busy loop would
     * fire hundreds of pulls in the same window; we assert the count stays in the
     * range the interval implies.
     */
    @Test
    void shortPollingSpacesOutEmptyPulls() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-sp").toString());
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve("store-sp").toString());
        config.setHoldScanIntervalMillis(200);

        int port = freePort();
        StoreBroker broker = new StoreBroker(config, port);
        broker.start();
        String directUrl = "127.0.0.1:" + port;
        StoreConsumer consumer = new StoreConsumer(directUrl, "short-poll-group");
        try {
            consumer.setSuspendTimeoutMillis(0);        // disable the broker hold
            consumer.setShortPollingIntervalMillis(500); // one empty round every 500ms
            consumer.subscribe("sp-demo", 0, messages -> { });
            consumer.start();

            Thread.sleep(2100);
            long pulls = broker.getMessageStore().getPullCount();
            // ~2100ms / 500ms = ~4-5 rounds (+ initial kick). Lower bound proves the
            // pump is actually running; the tight upper bound proves it backed off
            // instead of busy-looping (which would be in the hundreds).
            assertTrue(pulls >= 2, "expected multiple short-poll rounds, got " + pulls);
            assertTrue(pulls <= 15, "short polling should be spaced by the interval, got "
                    + pulls + " pulls in ~2.1s");
        } finally {
            consumer.shutdown();
            broker.shutdown();
            if (previous == null) {
                System.clearProperty("jaws.store.client.path");
            } else {
                System.setProperty("jaws.store.client.path", previous);
            }
        }
    }

    /**
     * Batch send over the real protocol: one wire call carries several records,
     * results come back in order, and the consumer pump delivers every one. This
     * exercises the large-frame write path a single put never produces.
     */
    @Test
    void batchSendDeliveredOverJawsProtocol() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-batch").toString());
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve("store-batch").toString());
        config.setHoldScanIntervalMillis(200);

        int port = freePort();
        StoreBroker broker = new StoreBroker(config, port);
        broker.start();
        String directUrl = "127.0.0.1:" + port;
        StoreProducer producer = new StoreProducer(directUrl);
        StoreConsumer consumer = new StoreConsumer(directUrl, "batch-group");
        try {
            int total = 20;
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(total);
            consumer.subscribe("batch-demo", 0, messages -> {
                for (MessageView view : messages) {
                    received.add(view.bodyAsString());
                    latch.countDown();
                }
            });
            consumer.start();

            List<StoreMessage> batch = new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                batch.add(StoreMessage.of("batch-demo", 0, "bm-" + i));
            }
            List<PutResult> results = producer.sendBatchSync(batch);
            assertEquals(total, results.size());
            for (int i = 0; i < total; i++) {
                assertEquals(PutStatus.PUT_OK, results.get(i).getStatus());
                assertEquals(i, results.get(i).getQueueOffset(), "batch offsets stay dense and ordered");
            }
            assertTrue(latch.await(15, TimeUnit.SECONDS), "consumer should receive the whole batch");
            assertEquals(total, received.size());
            List<String> expected = new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                expected.add("bm-" + i);
            }
            assertTrue(received.containsAll(expected), "every batch body must be delivered");
        } finally {
            consumer.shutdown();
            producer.shutdown();
            broker.shutdown();
            if (previous == null) {
                System.clearProperty("jaws.store.client.path");
            } else {
                System.setProperty("jaws.store.client.path", previous);
            }
        }
    }
}