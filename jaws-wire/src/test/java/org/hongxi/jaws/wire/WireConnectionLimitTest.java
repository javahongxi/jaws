package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.MessageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code maxServerConnections}, enforced once for every Netty
 * transport in {@code AbstractNettyServer}.
 * <p>
 * The parameter existed with a default of 100 000 and nothing ever read it,
 * while the server javadoc advertised "connection limiting" — a knob that
 * accepts a value and does nothing is worse than no knob, because the operator
 * believes there is a ceiling. These tests pin the three halves of the promise:
 * the over-limit connection is refused, the in-limit one is untouched, and
 * capacity returns when a connection closes.
 * <p>
 * The discriminator is the first byte of the stream: a connection refused at
 * accept time is closed before any HTTP/2 handler exists, so the client sees
 * end-of-stream, while an accepted one opens with the server SETTINGS frame.
 *
 * @author shenhongxi
 */
class WireConnectionLimitTest {

    /** Business handler that refuses everything: the cap is judged at accept time. */
    private static final MessageHandler UNTOUCHABLE = new MessageHandler() {
        @Override
        public java.util.concurrent.CompletableFuture<Object> handleAsync(Object message) {
            throw new IllegalStateException("unreachable in this test");
        }

        @Override
        public StreamSource<Object> handleStream(Request request, StreamSource<Object> in) {
            throw new IllegalStateException("unreachable in this test");
        }
    };

    private static final int READ_TIMEOUT_MS = 3000;

    private WireServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    void connectionOverTheLimitIsRefusedAndCapacityReturnsWhenOneCloses() throws Exception {
        int port = freePort();
        startServer(port, 1);

        Socket held = connect(port);
        assertFalse(refused(held), "the first connection is within the limit");

        try (Socket over = connect(port)) {
            assertTrue(refused(over),
                    "the connection past the limit must be closed at accept time");
        }

        held.close();
        try (Socket freed = connectUntilAccepted(port)) {
            assertFalse(refused(freed),
                    "capacity must return once an accepted connection closes");
        }
    }

    @Test
    void theDefaultLimitAcceptsConnections() throws Exception {
        int port = freePort();
        startServer(port, UrlParam.Server.MAX_CONNECTIONS.intValue());

        try (Socket a = connect(port); Socket b = connect(port)) {
            assertFalse(refused(a), "the default ceiling must not reject the first");
            assertFalse(refused(b), "the default ceiling must not reject the second");
        }
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private void startServer(int port, int maxConnections) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Server.MAX_CONNECTIONS.getName(), String.valueOf(maxConnections));
        server = new WireServer(new URL("wire", "127.0.0.1", port, "", params), UNTOUCHABLE);
        assertTrue(server.open(), "wire server should bind port " + port);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        return socket;
    }

    /**
     * @return true when the server closed the connection without sending a frame
     */
    private static boolean refused(Socket socket) throws IOException {
        try {
            return socket.getInputStream().read() == -1;
        } catch (SocketTimeoutException silent) {
            // Silent rather than closed: the connection is alive, no frame yet.
            return false;
        }
    }

    /**
     * Retries until the server accepts, so the freed-capacity assertion does not
     * race the close of the connection that held the slot.
     */
    private static Socket connectUntilAccepted(int port) throws IOException {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            Socket candidate = connect(port);
            if (!refused(candidate)) {
                return candidate;
            }
            candidate.close();
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("server never released the connection slot");
            }
            sleep(50);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
