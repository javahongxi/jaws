package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 surface of {@link ManagedChannel}: the per-call {@link WireCallOptions}
 * overload actually reaches the backend {@link WireClient} (a per-call deadline
 * beats the channel's static request timeout), and the grpc-style lifecycle
 * ({@code shutdown}/{@code shutdownNow}/{@code isShutdown}/{@code isTerminated}/
 * {@code awaitTermination}) behaves and rejects new calls once shut down.
 *
 * @author shenhongxi
 */
class ManagedChannelLifecycleTest {

    /** A controllable resolver so we can assert close()/shutdown() tore it down. */
    private static final class FakeResolver implements NameResolver {
        volatile boolean shutdown;

        FakeResolver(List<InetSocketAddress> initial) {
            this.current = initial;
        }

        private final List<InetSocketAddress> current;

        @Override
        public void start(Listener listener) {
            listener.onAddresses(current);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }
    }

    private static FakeResolver resolverAt(int port) {
        return new FakeResolver(List.of(new InetSocketAddress("127.0.0.1", port)));
    }

    // ---- silent HTTP/2 peer: handshakes, reads the one call, then stays quiet ----

    private static final byte[] EMPTY_SETTINGS_FRAME =
            {0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};

    private static int startSilentPeer(AtomicLong receivedBytes) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(4000);
                OutputStream out = socket.getOutputStream();
                out.write(EMPTY_SETTINGS_FRAME);
                out.flush();
                InputStream in = socket.getInputStream();
                receivedBytes.addAndGet(in.read(new byte[8192]));
                Thread.sleep(6000);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "mc-silent-peer");
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    @Test
    void perCallDeadlineOverridesChannelStaticTimeout() throws Exception {
        AtomicLong received = new AtomicLong();
        int port = startSilentPeer(received);
        // Channel-wide timeout is generous (4s); the per-call 300ms deadline must win.
        try (ManagedChannel ch = ManagedChannel.builder()
                .addAddress("127.0.0.1:" + port)
                .requestTimeout(4000)
                .connectTimeout(2000)
                .roundRobin().build()) {

            long start = System.currentTimeMillis();
            JawsAbstractException failure = assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
                try {
                    Response r = ch.unaryCall("interop.Greeter", "SayHello",
                            HealthCheckRequest.newBuilder().setService("never-answered").build(),
                            HealthCheckResponse.parser(),
                            null, WireCallOptions.DEFAULT.withDeadlineMs(300));
                    r.getValue();
                    throw new AssertionError("expected the call to fail on the per-call deadline");
                } catch (JawsAbstractException e) {
                    return e;
                }
            });
            long elapsed = System.currentTimeMillis() - start;

            String msg = String.valueOf(failure.getMessage());
            assertTrue(received.get() > 0, "the call must have reached the peer");
            assertTrue(msg.contains("timed out"), "cause must be a timeout, got: " + msg);
            assertTrue(msg.contains("300ms"),
                    "the per-call deadline budget must be shown, not the 4000ms static value: " + msg);
            assertEquals(JawsErrorCode.SERVICE_TIMEOUT, failure.getErrorCode());
            assertTrue(elapsed < 2000, "per-call 300ms not honoured (static was 4000ms): " + elapsed + "ms");
        }
    }

    @Test
    void gracefulShutdownDrainsThenTerminates() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            FakeResolver resolver = resolverAt(ss.getLocalPort());
            ManagedChannel ch = ManagedChannel.builder()
                    .nameResolver(resolver).roundRobin().build();
            assertEquals(1, ch.size());
            assertFalse(ch.isShutdown(), "fresh channel is not shut down");

            ch.shutdown();
            assertTrue(ch.isShutdown(), "shutdown() marks the channel shut down at once");
            assertTrue(resolver.shutdown, "shutdown() tears down the resolver");
            assertThrows(IllegalStateException.class,
                    () -> ch.unaryCall("interop.Greeter", "SayHello",
                            HealthCheckRequest.newBuilder().build(), HealthCheckResponse.parser()),
                    "new calls must be rejected once shutdown is requested");

            // No in-flight calls → the drain loop finds callbackMap empty and closes fast.
            assertTrue(ch.awaitTermination(3, TimeUnit.SECONDS),
                    "graceful shutdown should terminate quickly when nothing is in flight");
            assertTrue(ch.isTerminated());
            assertEquals(0, ch.size(), "terminating releases all backends");
        }
    }

    @Test
    void shutdownNowTerminatesSynchronously() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            FakeResolver resolver = resolverAt(ss.getLocalPort());
            ManagedChannel ch = ManagedChannel.builder().nameResolver(resolver).build();

            ch.shutdownNow();
            assertTrue(ch.isShutdown());
            assertTrue(ch.isTerminated(), "shutdownNow() releases inline (close(0) does not drain)");
            assertTrue(ch.awaitTermination(0, TimeUnit.MILLISECONDS));
            assertTrue(resolver.shutdown);
            assertEquals(0, ch.size());
            assertThrows(IllegalStateException.class,
                    () -> ch.unaryCall("interop.Greeter", "SayHello",
                            HealthCheckRequest.newBuilder().build(), HealthCheckResponse.parser()));
        }
    }

    @Test
    void closeIsEquivalentToShutdownNow() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            FakeResolver resolver = resolverAt(ss.getLocalPort());
            ManagedChannel ch = ManagedChannel.builder().nameResolver(resolver).build();
            ch.close();
            assertTrue(ch.isShutdown());
            assertTrue(ch.isTerminated());
            assertTrue(resolver.shutdown);
            assertEquals(0, ch.size());
        }
    }

    @Test
    void shutdownIsIdempotent() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            FakeResolver resolver = resolverAt(ss.getLocalPort());
            ManagedChannel ch = ManagedChannel.builder().nameResolver(resolver).build();
            ch.shutdown();
            ch.shutdown();               // second call must not double-drain or throw
            ch.shutdownNow();            // does not resurrect or re-close
            assertTrue(ch.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(ch.isTerminated());
        }
    }
}
