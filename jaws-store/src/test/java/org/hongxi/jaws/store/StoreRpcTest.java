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
}