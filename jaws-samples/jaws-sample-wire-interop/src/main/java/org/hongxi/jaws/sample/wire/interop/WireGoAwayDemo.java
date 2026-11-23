package org.hongxi.jaws.sample.wire.interop;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Proves that {@link WireClient} recovers from a server-initiated GOAWAY
 * sent by a standard grpc-java server with {@code maxConnectionAge} —
 * exactly the "server recycles long-lived connections" scenario.
 * <p>
 * The grpc-java server is configured with {@code maxConnectionAge(2s)} so
 * it forces a GOAWAY (NO_ERROR, graceful connection recycling) shortly after
 * the connection is established. The demo pins the client-side contract:
 * <ol>
 *   <li>GOAWAY is treated as graceful, not a failure: connectivity goes
 *       READY → IDLE, never through TRANSIENT_FAILURE</li>
 *   <li>The client reconnects asynchronously on its own: READY is restored
 *       without any RPC being issued to trigger a lazy reconnect</li>
 *   <li>The next RPC after recovery succeeds on the fresh connection</li>
 * </ol>
 * Self-contained: starts and stops its own grpc-java server on port 50067.
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.WireGoAwayDemo"
 * </pre>
 */
public class WireGoAwayDemo {

    private static final int GRPC_PORT = 50067;

    /** Failed checks; a non-zero count must fail the process so run-sample.sh counts it. */
    private static int failures = 0;

    private static void fail(String message) {
        System.err.println("ERROR: " + message);
        failures++;
    }

    public static void main(String[] args) throws Exception {
        // grpc-java server that recycles every connection after ~2s:
        // it sends GOAWAY(NO_ERROR) at maxConnectionAge, then force-closes
        // after the 1s grace period.
        Server grpcServer = NettyServerBuilder.forPort(GRPC_PORT)
                .maxConnectionAge(2, TimeUnit.SECONDS)
                .maxConnectionAgeGrace(1, TimeUnit.SECONDS)
                .addService(new GreeterGrpc.GreeterImplBase() {
                    @Override
                    public void sayHello(HelloRequest request,
                                         StreamObserver<HelloReply> responseObserver) {
                        responseObserver.onNext(HelloReply.newBuilder()
                                .setMessage("Hello, " + request.getName() + "! (from grpc-java)")
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        System.out.println("[server] grpc-java started on port " + GRPC_PORT
                + " with maxConnectionAge=2s");

        WireClient client = new WireClient(buildUrl(Map.of(
                "connectTimeout", "5000", "requestTimeout", "5000")));
        try {
            client.open();

            // Record every connectivity transition from here on
            List<String> transitions = new CopyOnWriteArrayList<>();
            client.getConnectivityTracker().addListener(
                    (previous, current) -> transitions.add(previous + " → " + current));

            // Warm up: prove the RPC path works on the first connection
            String first = sayHello(client, "goaway-1");
            System.out.println("[client] first RPC ok: " + first);

            // The server must GOAWAY at ~2s; the client must go READY → IDLE
            if (!awaitCondition(() -> transitions.contains("READY → IDLE"), 10_000)) {
                fail("no READY → IDLE transition within 10s "
                        + "(server GOAWAY not observed), saw: " + transitions);
            } else {
                System.out.println("[client] GOAWAY observed, connectivity went READY → IDLE");
            }

            // Async reconnect must restore READY on its own — no RPC is
            // issued here, so a lazy reconnect-on-request cannot mask a
            // broken reconnectOnGoAway path.
            if (!awaitCondition(() -> client.getConnectivityTracker().isReady(), 5_000)) {
                fail("connectivity did not return to READY within 5s after GOAWAY "
                        + "(async reconnect broken), saw: " + transitions);
            } else {
                System.out.println("[client] async reconnect restored READY without any RPC");
            }

            // The next RPC must succeed on the fresh connection
            try {
                String after = sayHello(client, "after-goaway");
                System.out.println("[client] RPC after GOAWAY ok (reconnected): " + after);
            } catch (Exception e) {
                fail("RPC after GOAWAY recovery failed: " + e);
            }

            // GOAWAY is graceful recycling, not a connection failure
            boolean noFailureState = transitions.stream().noneMatch(t -> t.contains("TRANSIENT_FAILURE"));
            if (!noFailureState) {
                fail("GOAWAY must not surface as TRANSIENT_FAILURE, saw: " + transitions);
            }
            System.out.println("[client] state transitions: " + transitions);
        } finally {
            client.close();
            grpcServer.shutdownNow();
            grpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }

        if (failures == 0) {
            System.out.println("\n=== WireGoAwayDemo Passed ===");
        } else {
            System.err.println("\n=== WireGoAwayDemo FAILED: " + failures + " check(s) ===");
        }

        // Force exit (Netty non-daemon threads prevent JVM exit), carrying the
        // verdict in the exit code so the runner counts this demo honestly.
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String sayHello(WireClient client, String name) {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("interop.Greeter");
        request.setMethodName("SayHello");
        request.setArguments(new Object[]{
                HelloRequest.newBuilder().setName(name).build()
        });
        return ((HelloReply) client.request(request, HelloReply.parser()).getValue()).getMessage();
    }

    /** Polls the condition every 20ms until it holds or the deadline passes. */
    private static boolean awaitCondition(java.util.function.BooleanSupplier condition,
                                          long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static URL buildUrl(Map<String, String> extraParams) {
        Map<String, String> params = new HashMap<>(extraParams);
        return new URL("grpc", "localhost", GRPC_PORT, "interop.Greeter", params);
    }
}