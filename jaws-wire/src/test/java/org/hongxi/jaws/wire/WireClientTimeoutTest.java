package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsAbstractException;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Pins the client-side request timeout on the wire transport: when a peer accepts
 * the connection, completes the HTTP/2 handshake well enough for the request to go
 * out, and then never answers, {@code requestTimeout} must still fail the call
 * instead of letting the caller block forever.
 * <p>
 * The stub is deliberately a silent sink rather than a live {@link WireServer}: a
 * real server would answer, and the deadline the server enforces for itself would
 * mask whether the client armed its own timer.
 *
 * @author shenhongxi
 */
class WireClientTimeoutTest {

    /** An empty HTTP/2 SETTINGS frame: length 0, type 0x4, flags 0, stream 0. */
    private static final byte[] EMPTY_SETTINGS_FRAME =
            {0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00};

    private static final int REQUEST_TIMEOUT_MS = 300;

    /**
     * Accepts connections, answers only the SETTINGS frame a client needs before it
     * writes its request, then swallows everything and never replies.
     *
     * @return the bound port; the accepting thread is a daemon
     */
    private static int startSilentServer(AtomicLong receivedBytes) throws IOException {
        ServerSocket server = new ServerSocket(0);
        server.setReuseAddress(true);
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    OutputStream out = socket.getOutputStream();
                    out.write(EMPTY_SETTINGS_FRAME);
                    out.flush();
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        receivedBytes.addAndGet(read);
                    }
                } catch (IOException e) {
                    return; // socket or server closed by the test
                }
            }
        }, "wire-silent-server");
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    /**
     * Same handshake, but the peer hangs up instead of answering: the caller must
     * still get a typed failure it can catch, not a bare RuntimeException.
     */
    @Test
    void closedStreamFailsWithATypedException() throws Exception {
        ServerSocket server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.getOutputStream().write(EMPTY_SETTINGS_FRAME);
                socket.getOutputStream().flush();
                socket.getInputStream().read(new byte[4096]);
                // Let the call finish being written, then hang up without answering,
                // so the only failure left is the stream going inactive.
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException ignored) {
                // the accepted socket closing ends the stub
            }
        }, "wire-hangup-server");
        thread.setDaemon(true);
        thread.start();

        Map<String, String> parameters = new HashMap<>();
        parameters.put("connectTimeout", "2000");
        parameters.put("requestTimeout", "5000");
        URL url = new URL("wire", "127.0.0.1", server.getLocalPort(), "interop.Greeter", parameters);

        WireClient client = new WireClient(url);
        assertTrue(client.open(), "client should connect to the hanging-up server");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            JawsAbstractException failure = assertThrows(JawsAbstractException.class, () ->
                    client.request(helloRequest(), HealthCheckResponse.parser()).getValue());
            assertTrue(String.valueOf(failure.getMessage()).contains("stream closed"),
                    "the failure must say what happened, got: " + failure.getMessage());
        });

        client.close();
    }

    private static Request helloRequest() {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("interop.Greeter");
        request.setMethodName("SayHello");
        request.setArguments(new Object[]{
                HealthCheckRequest.newBuilder().setService("slow-never-answers").build()
        });
        return request;
    }

    @Test
    void unaryCallFailsWithRequestTimeoutWhenPeerNeverAnswers() throws Exception {
        AtomicLong receivedBytes = new AtomicLong();
        int port = startSilentServer(receivedBytes);

        Map<String, String> parameters = new HashMap<>();
        parameters.put("connectTimeout", "2000");
        parameters.put("requestTimeout", String.valueOf(REQUEST_TIMEOUT_MS));
        URL url = new URL("wire", "127.0.0.1", port, "interop.Greeter", parameters);

        WireClient client = new WireClient(url);
        assertTrue(client.open(), "client should connect to the silent server");

        // The whole point: without an armed per-request timer this blocks forever,
        // so bound it preemptively and let the test report the hang.
        long start = System.currentTimeMillis();
        JawsAbstractException failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(JawsAbstractException.class, () -> {
                    Response response = client.request(helloRequest(), HealthCheckResponse.parser());
                    response.getValue();
                }));
        long elapsed = System.currentTimeMillis() - start;

        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains("timed out"), "cause must be named, got: " + message);
        assertTrue(message.contains("interop.Greeter.SayHello"), "call must be named, got: " + message);
        assertTrue(message.contains(REQUEST_TIMEOUT_MS + "ms"), "budget must be shown, got: " + message);

        assertTrue(receivedBytes.get() > 0, "the request must have reached the peer");
        assertTrue(elapsed >= REQUEST_TIMEOUT_MS / 2,
                "firing far earlier than requestTimeout means something else failed: " + elapsed + "ms");
        assertTrue(elapsed < 3000, "requestTimeout=" + REQUEST_TIMEOUT_MS + "ms was not honoured: " + elapsed + "ms");

        client.close();
    }
}
