package org.hongxi.jaws.store;

import org.hongxi.jaws.store.client.StoreConsumer;
import org.hongxi.jaws.store.client.StoreProducer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        }
    }

    /**
     * Batch send over the real protocol: one wire call carries several records,
     * results come back in order, and the consumer pump delivers every one. This
     * exercises the large-frame write path a single put never produces.
     */
    @Test
    void batchSendDeliveredOverJawsProtocol() throws Exception {
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
        }
    }

    /**
     * Regression for shutdown noise: when a broker is already gone the lease hand-back
     * cannot land, and its in-memory lease dies with it — an expected outcome, not a
     * fault. Attaching a direct-mode consumer then taking the broker down before
     * consumer.shutdown() reproduces the path that used to log a WARN with a stack. The
     * release must instead take the quiet debug skip, producing no WARN at all.
     */
    @Test
    void shutdownAgainstDownBrokerSkipsLeaseReleaseQuietly() throws Exception {
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve("store-quiet").toString());
        config.setHoldScanIntervalMillis(200);

        int port = freePort();
        StoreBroker broker = new StoreBroker(config, port);
        broker.start();
        String directUrl = "127.0.0.1:" + port;
        StoreConsumer consumer = new StoreConsumer(directUrl, "quiet-shutdown-group");

        // Capture StoreConsumer's own log while the shutdown path runs.
        Logger log = (Logger) LoggerFactory.getLogger(StoreConsumer.class);
        Level previous = log.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        log.setLevel(Level.DEBUG);

        boolean brokerDown = false;
        boolean consumerDown = false;
        try {
            consumer.subscribe("quiet-demo", 0, messages -> { });
            consumer.start();

            // Broker dies while the consumer still holds the queue attached: direct mode
            // never re-detects the loss, so shutdown still attempts the hand-back.
            broker.shutdown();
            brokerDown = true;
            consumer.shutdown();
            consumerDown = true;

            boolean warned = appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("Lease release on shutdown failed"));
            boolean skipped = appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.DEBUG
                            && e.getFormattedMessage().contains("Skipping lease hand-back"));
            assertFalse(warned, "shutdown must not warn when the broker is unreachable");
            assertTrue(skipped, "the unreachable hand-back should take the quiet debug skip");
        } finally {
            log.detachAppender(appender);
            appender.stop();
            log.setLevel(previous);
            if (!consumerDown) {
                consumer.shutdown();
            }
            if (!brokerDown) {
                broker.shutdown();
            }
        }
    }
}