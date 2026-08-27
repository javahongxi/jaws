package org.hongxi.jaws.sample.wire.interop;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireMethodHandler;
import org.hongxi.jaws.wire.WireServer;
import org.hongxi.jaws.wire.WireServiceRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end verification of the wire server's gRPC keepalive policy
 * (PERMIT_KEEPALIVE_TIME semantics, gRFC A8) against a standard grpc-java
 * client — exactly the "external gRPC client calls jaws wire" scenario.
 * <p>
 * Client-side fact (measured, and documented in grpc-java):
 * {@code KeepAliveManager.clampKeepAliveTimeInNanos} bumps any configured
 * keepAliveTime below 10s up to 10s, so a real grpc-java client PINGs at
 * most once every 10 seconds. The scenarios below are designed around that
 * cadence (observed: 3 PINGs at exact 10.005s intervals during idle).
 * <p>
 * Scenario 1 (permitted keepalive): client PINGs every ~10s against a 2s
 * permit interval; every PING must be ACKed (Netty codec auto-ACK) and the
 * connection must stay READY across a 25s idle window (~3 PING/ACK cycles).
 * <p>
 * Scenario 2 (too_many_pings enforcement): permit interval 12s > client
 * cadence 10s, so the 2nd PING is too soon; the server must GOAWAY
 * with ENHANCE_YOUR_CALM ("too_many_pings") and close the connection. The
 * client observes this as connectivity flapping (READY -> IDLE/CONNECTING);
 * the server logs the WARN with GOAWAY.
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.WireKeepaliveInteropDemo"
 * </pre>
 */
public class WireKeepaliveInteropDemo {

    private static final int PERMIT_PORT = 50091;
    private static final int ENFORCE_PORT = 50092;

    public static void main(String[] args) throws Exception {
        permittedKeepaliveAcks();
        tooManyPingsEnforced();
        System.out.println();
        System.out.println("=== ALL KEEPALIVE INTEROP CHECKS PASSED ===");
        System.exit(0);
    }

    /**
     * Client PINGs every ~10s (clamped cadence), permitted interval 2s:
     * every PING is ACKed and the connection stays READY for the whole
     * 25s idle window (~3 PING/ACK round trips).
     */
    private static void permittedKeepaliveAcks() throws Exception {
        System.out.println("--- Scenario 1: permitted keepalive PINGs are ACKed (~30s) ---");
        WireServer server = startWireServer(PERMIT_PORT, 2000L);
        try {
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress("localhost", PERMIT_PORT)
                    .usePlaintext()
                    // Clamped to 10s by grpc-java; stated explicitly for clarity
                    .keepAliveTime(10, TimeUnit.SECONDS)
                    .keepAliveTimeout(5, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();
            try {
                // Warm up: establish the connection and prove the RPC path works
                String reply = sayHello(channel, "keepalive-1");
                System.out.println("[client] first RPC ok: " + reply);

                // From here on, record every connectivity state transition
                List<ConnectivityState> transitions = new CopyOnWriteArrayList<>();
                watchState(channel, channel.getState(false), transitions, null);

                // Idle 25s: ~3 keepalive PINGs are sent at the clamped 10s
                // cadence. If the server failed to ACK, keepAliveTimeout(5s)
                // would kill the connection and we would see state transitions.
                Thread.sleep(25_000);

                String reply2 = sayHello(channel, "keepalive-2");
                System.out.println("[client] RPC after 25s idle ok: " + reply2);

                boolean stable = transitions.isEmpty();
                System.out.println("[client] state transitions during idle: "
                        + (stable ? "none (connection stayed READY, PINGs ACKed)" : transitions));
                assertTrue(stable, "scenario 1: connection must stay READY "
                        + "(PINGs ACKed), but saw transitions: " + transitions);
                System.out.println("Scenario 1 PASSED (server log should show ~3 "
                        + "'WireKeepalive received PING' lines, no WARN)\n");
            } finally {
                channel.shutdownNow();
            }
        } finally {
            server.close(0);
        }
    }

    /**
     * Permitted interval 12s > client's clamped 10s cadence: the 2nd PING
     * (~t=20s) arrives too soon, so the server must GOAWAY too_many_pings
     * and close the connection. Detected client-side as connectivity
     * flapping and/or ENHANCE_YOUR_CALM UNAVAILABLE failures.
     */
    private static void tooManyPingsEnforced() throws Exception {
        System.out.println("--- Scenario 2: over-frequent PINGs get GOAWAY too_many_pings (~25s) ---");
        WireServer server = startWireServer(ENFORCE_PORT, 12_000L);
        try {
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress("localhost", ENFORCE_PORT)
                    .usePlaintext()
                    .keepAliveTime(10, TimeUnit.SECONDS)
                    .keepAliveTimeout(5, TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();
            try {
                String reply = sayHello(channel, "enforce");
                System.out.println("[client] first RPC ok: " + reply);

                List<ConnectivityState> transitions = new CopyOnWriteArrayList<>();
                CountDownLatch flapped = new CountDownLatch(1);
                watchState(channel, channel.getState(false), transitions, flapped);

                // Stay IDLE: any inbound data (even RPC responses) defers the
                // next keepalive PING in grpc-java (KeepAliveManager's
                // PING_DELAYED), so hammering RPCs would suppress PINGs.
                // The clamped cadence means the enforcing GOAWAY lands at
                // ~t=10s (or ~t=20s), well within the 25s deadline.
                boolean flappingSeen = flapped.await(25, TimeUnit.SECONDS);

                // After GOAWAY the channel auto-reconnects; prove it recovers
                String after = sayHello(channel, "after-goaway");
                System.out.println("[client] RPC after GOAWAY ok (reconnected): " + after);
                boolean flappedNonReady = transitions.stream()
                        .anyMatch(s -> s != ConnectivityState.READY);
                System.out.println("[client] state transitions: " + transitions);
                // Flapping proves the server actively closed the connection in
                // response to over-frequent PINGs; the server WARN log proves
                // the exact reason (GOAWAY too_many_pings).
                assertTrue(flappingSeen && flappedNonReady,
                        "scenario 2: expected connection flapping after "
                                + "GOAWAY too_many_pings, saw: " + transitions);
                System.out.println("Scenario 2 PASSED (server log should show "
                        + "'closing with GOAWAY too_many_pings')\n");
            } finally {
                channel.shutdownNow();
            }
        } finally {
            server.close(0);
        }
    }

    private static WireServer startWireServer(int port, long permitPingIntervalMs) {
        WireServiceRegistry registry = new WireServiceRegistry();
        registry.register("interop.Greeter", "SayHello", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                HelloRequest req = (HelloRequest) request;
                return HelloReply.newBuilder()
                        .setMessage("Hello, " + req.getName())
                        .build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HelloRequest.parser();
            }

            @Override
            public Message getResponseDefaultInstance() {
                return HelloReply.getDefaultInstance();
            }
        });

        Map<String, String> params = Map.of(
                "permitPingIntervalMs", String.valueOf(permitPingIntervalMs));
        URL url = new URL("wire", "localhost", port, "interop.Greeter", params);
        WireServer server = new WireServer(url, registry);
        server.open();
        return server;
    }

    private static String sayHello(ManagedChannel channel, String name) {
        HelloReply reply = GreeterGrpc.newBlockingStub(channel)
                .sayHello(HelloRequest.newBuilder().setName(name).build());
        return reply.getMessage();
    }

    /**
     * Recursively watch connectivity state transitions of the channel.
     *
     * @param notify optional latch counted down on the first transition
     */
    private static void watchState(ManagedChannel channel, ConnectivityState current,
                                   List<ConnectivityState> transitions, CountDownLatch notify) {
        channel.notifyWhenStateChanged(current, () -> {
            ConnectivityState next = channel.getState(false);
            transitions.add(next);
            if (notify != null) {
                notify.countDown();
            }
            watchState(channel, next, transitions, notify);
        });
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
