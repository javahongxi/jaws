package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native client performs the {@code ServerCheckRequest} handshake before it opens
 * a notification stream, exactly as nacos-client does.
 *
 * <p>A black-box check cannot catch a dropped handshake on its own: the server
 * registers the connection under the same per-TCP id whether or not the unary
 * handshake ran, so the stream would still come up. The handshake counter on a
 * freshly started node is what pins the unary call itself onto the connection path
 * — remove {@code serverCheck()} from the client and the count stays at zero here.
 *
 * @author shenhongxi
 */
@Timeout(60)
class HandshakeSemanticsTest {

    private HarborServer server;
    private int port;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void connectingIssuesExactlyOneServerCheckHandshake() throws Exception {
        startNode();
        assertEquals(0, server.answeredServerChecks(), "a fresh node has answered nothing");

        try (HarborClient client = new HarborClient("127.0.0.1", port)) {
            client.registerInstance("handshake", instance("127.0.0.1", 9500));

            // One node, one initial connection: the walk attaches without failing over,
            // so the single handshake is both the whole story and the mutation tripwire.
            assertEquals(1, server.answeredServerChecks(),
                    "the client must unary-check the node before opening its stream");
            // And it worked — the registration landed, so the handshake did not gate us out.
            assertTrue(client.getInstances("handshake").stream()
                            .anyMatch(each -> each.getPort() == 9500),
                    "a checked connection still carries real traffic");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void startNode() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = new HarborServer(new URL("harbor", "0.0.0.0", port, ""));
        server.start();
        awaitListening(port);
    }

    private static org.hongxi.jaws.harbor.model.Instance instance(String ip, int listenPort) {
        org.hongxi.jaws.harbor.model.Instance instance =
                new org.hongxi.jaws.harbor.model.Instance();
        instance.setIp(ip);
        instance.setPort(listenPort);
        return instance;
    }

    /** Poll the port until it accepts instead of guessing a fixed startup delay. */
    private static void awaitListening(int listenPort) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", listenPort), 200);
                return;
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }
        throw new IllegalStateException("harbor port " + listenPort + " never accepted");
    }
}
