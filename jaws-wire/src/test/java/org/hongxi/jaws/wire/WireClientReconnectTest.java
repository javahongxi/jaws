package org.hongxi.jaws.wire;

import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a long-lived {@link WireClient} behaves across the death and return
 * of its peer — the situation every cluster member lives with (Harbor's Distro
 * verify loop keeps one {@code WireClient} per peer for the process lifetime).
 * <p>
 * A peer connection that dies (crash, {@code kill -9}, host reboot) never says
 * goodbye, so no GOAWAY arrives and no proactive reconnect is attempted: the
 * client can only discover the loss lazily, on the next call. Two properties
 * must hold and are asserted here:
 * <ul>
 *   <li>the very next call after the peer returns must succeed — availability is
 *       re-established by the same lazy reconnect the jaws client uses, not by a
 *       pre-flight guard that short-circuits it (the measured failure: once the
 *       channel was gone every later call threw without ever dialling again, so
 *       anti-entropy with a revived peer stayed broken until a process restart);</li>
 *   <li>an attempt that fails while the peer is still down must be repeatable — a
 *       failed reconnect may not poison the client, and must surface as a named
 *       {@link JawsAbstractException} rather than a bare {@link NullPointerException}
 *       escaping the transport layer.</li>
 * </ul>
 * The peer is a real {@link WireServer} whose built-in {@code grpc.health.v1.Health}
 * service is the call target, restarted on the same port: the client's identity IS
 * that port, so a different port would answer a different question.
 *
 * @author shenhongxi
 */
class WireClientReconnectTest {

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /**
     * Bind a wire server, retrying briefly: the port was just released by the
     * server we killed, and TIME_WAIT can make an immediate rebind fail.
     */
    private static WireServer startServer(int port) {
        URL url = new URL("wire", "0.0.0.0", port, "");
        for (int attempt = 1; ; attempt++) {
            try {
                WireServer server = new WireServer(url, new WireHandlerRegistry());
                server.open();
                return server;
            } catch (Exception e) {
                if (attempt >= 10) {
                    throw new IllegalStateException("cannot bind wire server on " + port, e);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
    }

    private static WireClient openClient(int port) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("connectTimeout", "2000");
        parameters.put("requestTimeout", "3000");
        WireClient client = new WireClient(new URL(
                "wire", "127.0.0.1", port, "grpc.health.v1.Health", parameters));
        assertTrue(client.open(), "client should connect to the wire server");
        return client;
    }

    /** Query the server's overall health (empty service name = aggregate status). */
    private static HealthCheckResponse check(WireClient client) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(WireHealthService.SERVICE_NAME);
        request.setMethodName("Check");
        request.setArguments(new Object[]{
                HealthCheckRequest.newBuilder().setService("").build()
        });
        return (HealthCheckResponse) client.request(request, HealthCheckResponse.parser())
                .getValue();
    }

    /** Poll until the client has observed that its peer connection is gone. */
    private static void awaitUnavailable(WireClient client) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (!client.isAvailable()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("the client never noticed the peer's death; this test no "
                + "longer reproduces the scenario it pins");
    }

    @Test
    void nextCallRecoversWhenThePeerReturnsOnTheSamePort() throws Exception {
        int port = freePort();
        WireServer server = startServer(port);
        WireClient client = openClient(port);

        assertEquals(ServingStatus.SERVING, check(client).getStatus());

        server.close();
        awaitUnavailable(client);
        WireServer revived = startServer(port);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertEquals(ServingStatus.SERVING, check(client).getStatus(),
                            "the revived peer must answer the very next call"),
                    "a call issued after the peer came back never completed — the client is wedged");
        } finally {
            client.close();
            revived.close();
        }
    }

    @Test
    void failedAttemptWhilePeerIsDownDoesNotPoisonLaterRecovery() throws Exception {
        int port = freePort();
        WireServer server = startServer(port);
        WireClient client = openClient(port);
        check(client);

        server.close();
        awaitUnavailable(client);

        // During the outage the call must fail fast and by name — not hang, and not
        // leak an NPE from the slot a failed reconnect left empty.
        JawsAbstractException during = assertThrows(JawsAbstractException.class,
                () -> check(client),
                "a call against a dead peer must fail as a named transport error");
        assertTrue(during.getMessage() != null && !during.getMessage().isBlank(),
                "the failure must say what went wrong");

        WireServer revived = startServer(port);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertEquals(ServingStatus.SERVING, check(client).getStatus(),
                            "one failed reconnect attempt may not wedge the client forever"),
                    "the client never recovered after a failed attempt — anti-entropy with a "
                            + "revived peer would stay broken until the process restarts");
        } finally {
            client.close();
            revived.close();
        }
    }

    @Test
    void closedClientFailsInsteadOfResurrectingItsConnections() throws Exception {
        int port = freePort();
        WireServer server = startServer(port);
        WireClient client = openClient(port);
        check(client);

        client.close();
        assertFalse(client.isAvailable(), "a closed client must report itself unavailable");

        assertThrows(JawsAbstractException.class, () -> check(client),
                "close() must stay terminal: dropping the availability guard may not let a "
                        + "closed client dial again behind its owner's back");
        server.close();
    }
}
