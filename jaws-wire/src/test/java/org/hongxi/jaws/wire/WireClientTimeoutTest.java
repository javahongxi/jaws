package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a wire client call ends when the peer never answers, for the three
 * shapes a peer can leave us in:
 * <ul>
 *   <li><b>silent connection</b> — nothing but the per-request timeout can free
 *       the caller, so {@code requestTimeout} must fire and must be reported as a
 *       timeout (40003) rather than a generic service failure;</li>
 *   <li><b>GOAWAY</b> — the pending call must fail at once, not after its
 *       budget, otherwise every connection teardown would read as a timeout;</li>
 *   <li><b>stream reset</b> — Netty consumes an inbound {@code RST_STREAM} inside
 *       {@code Http2FrameCodec}: measured on 4.1.132 it reaches neither the parent
 *       pipeline nor the client-created stream channel (no channelRead, no user
 *       event, no channelInactive), so only the local timeout recovers the caller.
 *       That case is asserted rather than assumed: when a Netty release starts
 *       delivering resets to the client side, this test goes red and the correct
 *       response is to handle the reset instead of timing out.</li>
 * </ul>
 * The stub is a raw socket on purpose. A real {@link WireServer} would answer, and
 * a grpc-java server enforces the propagated deadline, either of which hides
 * whether the client armed its own timer.
 *
 * @author shenhongxi
 */
class WireClientTimeoutTest {

    /** An empty HTTP/2 SETTINGS frame: length 0, type 0x4, flags 0, stream 0. */
    private static final byte[] EMPTY_SETTINGS_FRAME =
            {0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};

    /** GOAWAY: length 8, type 0x7, last stream id 1, error NO_ERROR. */
    private static final byte[] GOAWAY_FRAME = {
            0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00};

    /** RST_STREAM: length 4, type 0x3, stream 1, error CANCEL. */
    private static final byte[] RST_STREAM_ON_1 = {
            0x00, 0x00, 0x04, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x08};

    /**
     * Handshakes, swallows one read (the call), and then sends {@code frame} — or
     * nothing when it is empty — while holding the connection open.
     *
     * @param frame            the frame to send after the request, or empty for a silent peer
     * @param holdOpenMs       how long to keep the connection alive afterwards
     * @param receivedBytes    counts bytes read, to prove the call reached the peer
     * @return the port the stub listens on
     */
    private static int startPeer(byte[] frame, long holdOpenMs, AtomicLong receivedBytes)
            throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                OutputStream out = socket.getOutputStream();
                out.write(EMPTY_SETTINGS_FRAME);
                out.flush();
                InputStream in = socket.getInputStream();
                try {
                    receivedBytes.addAndGet(in.read(new byte[8192]));
                } catch (IOException e) {
                    return; // no call arrived; the test will fail on its assertion
                }
                Thread.sleep(300); // let the call finish being written
                if (frame.length > 0) {
                    out.write(frame);
                    out.flush();
                }
                Thread.sleep(holdOpenMs);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "wire-stub-peer");
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    private static WireClient openClient(int port, int requestTimeoutMs) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("connectTimeout", "2000");
        parameters.put("requestTimeout", String.valueOf(requestTimeoutMs));
        WireClient client = new WireClient(new URL(
                "wire", "127.0.0.1", port, "interop.Greeter", parameters));
        assertTrue(client.open(), "client should connect to the stub peer");
        return client;
    }

    private static Request helloRequest() {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("interop.Greeter");
        request.setMethodName("SayHello");
        request.setArguments(new Object[]{
                HealthCheckRequest.newBuilder().setService("never-answered").build()
        });
        return request;
    }

    /** The blocking read is where the failure surfaces, so the test must consume it. */
    private static JawsAbstractException failCall(WireClient client) {
        return assertThrows(JawsAbstractException.class, () -> {
            Response response = client.request(helloRequest(), HealthCheckResponse.parser());
            response.getValue();
        });
    }

    @Test
    void silentPeerIsBackstoppedByTheRequestTimeout() throws Exception {
        AtomicLong receivedBytes = new AtomicLong();
        WireClient client = openClient(startPeer(new byte[0], 6000, receivedBytes), 300);

        long start = System.currentTimeMillis();
        JawsAbstractException failure = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> failCall(client));
        long elapsed = System.currentTimeMillis() - start;

        String message = String.valueOf(failure.getMessage());
        assertTrue(receivedBytes.get() > 0, "the call must have reached the peer");
        assertTrue(message.contains("timed out"), "the cause must be named, got: " + message);
        assertTrue(message.contains("interop.Greeter.SayHello"), "the call must be named, got: " + message);
        assertTrue(message.contains("300ms"), "the budget must be shown, got: " + message);
        assertEquals(JawsErrorCode.SERVICE_TIMEOUT, failure.getErrorCode(),
                "a local timeout must not be reported as a generic service failure");
        assertTrue(elapsed >= 150, "firing far earlier than the budget hides another fault: " + elapsed);
        assertTrue(elapsed < 2000, "requestTimeout=300ms was not honoured: " + elapsed + "ms");

        client.close();
    }

    @Test
    void goAwayFailsThePendingCallImmediately() throws Exception {
        AtomicLong receivedBytes = new AtomicLong();
        WireClient client = openClient(startPeer(GOAWAY_FRAME, 5000, receivedBytes), 3000);

        long start = System.currentTimeMillis();
        JawsAbstractException failure = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> failCall(client));
        long elapsed = System.currentTimeMillis() - start;

        String message = String.valueOf(failure.getMessage());
        assertTrue(elapsed < 2000, "GOAWAY must fail the call at once, not after 3s: " + elapsed + "ms");
        assertTrue(message.contains("requestId="), "the failure must identify the call: " + message);

        client.close();
    }

    @Test
    void streamResetIsInvisibleToTheClientAndBackstoppedByTheTimeout() throws Exception {
        AtomicLong receivedBytes = new AtomicLong();
        WireClient client = openClient(startPeer(RST_STREAM_ON_1, 6000, receivedBytes), 600);

        long start = System.currentTimeMillis();
        JawsAbstractException failure = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> failCall(client));
        long elapsed = System.currentTimeMillis() - start;

        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("timed out"),
                "Netty 4.1.132 never surfaces an inbound RST_STREAM to the client side "
                        + "(no channelRead, no user event, no channelInactive), so only the "
                        + "local budget can end this call, got: " + message);
        assertEquals(JawsErrorCode.SERVICE_TIMEOUT, failure.getErrorCode(),
                "from the caller's side it is still a timeout: " + message);
        assertTrue(elapsed >= 300 && elapsed < 2500,
                "the reset must not be mistaken for a fast failure: " + elapsed + "ms");

        client.close();
    }
}
