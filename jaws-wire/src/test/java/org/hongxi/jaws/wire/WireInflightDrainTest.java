package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the in-flight accounting a wire server feeds into graceful
 * shutdown. {@code AbstractNettyServer.drainInflightRequests} waits on that
 * counter, so if the wire transport never moves it, unexport stops waiting
 * immediately and cuts off whatever the business pool is still working on.
 * <p>
 * The binary and http2 transports count their own streams; wire was the one
 * transport that did not, which made its graceful shutdown a no-op that looked
 * like it worked.
 *
 * @author shenhongxi
 */
class WireInflightDrainTest {

    /** Blocks each request until the test releases it, counting entries. */
    private static final class BlockingHandler implements MessageHandler {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<Object> handleAsync(Object message) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(HealthCheckResponse.newBuilder()
                    .setStatus(ServingStatus.SERVING).build());
        }

        @Override
        public StreamSource<Object> handleStream(Request request, StreamSource<Object> in) {
            throw new UnsupportedOperationException("unary only");
        }
    }

    private WireServer server;
    private WireClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    void anInFlightRequestIsCountedAndHoldsTheDrainWindow() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        BlockingHandler handler = new BlockingHandler();
        server = new WireServer(new URL("wire", "127.0.0.1", port, ""), handler);
        assertTrue(server.open(), "wire server should bind port " + port);
        client = new WireClient(new URL("wire", "127.0.0.1", port, ""));
        assertTrue(client.open(), "wire client should connect");

        Thread call = new Thread(() -> client.request(pingRequest(), HealthCheckResponse.parser()));
        call.setDaemon(true);
        call.start();

        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "request should reach the handler");
        // The counter is what drain waits on: an accepted-but-unfinished
        // business stream must be visible in it.
        awaitCount(1);

        long start = System.nanoTime();
        server.drainInflightRequests(400);
        long waitedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(waitedMs >= 350,
                "drain should wait out an in-flight request, waited only " + waitedMs + "ms");
        assertEquals(1, server.getInflightRequestCount(), "still in flight while blocked");

        handler.release.countDown();
        awaitCount(0);
        call.join(5000);

        // With nothing in flight the same call must return promptly.
        start = System.nanoTime();
        server.drainInflightRequests(2000);
        long freeMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(freeMs < 1500, "drain should not wait when idle, took " + freeMs + "ms");
    }

    private static DefaultRequest pingRequest() {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("demo.Multi");
        request.setMethodName("Ping");
        request.setRequestId(1L);
        // The client encodes a protobuf message; the pipeline hands the
        // server-side handler the raw bytes.
        request.setArguments(new Object[]{
                HealthCheckRequest.newBuilder().setService("probe").build()});
        return request;
    }

    private void awaitCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (server.getInflightRequestCount() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, server.getInflightRequestCount(),
                "in-flight count never reached " + expected);
    }
}
