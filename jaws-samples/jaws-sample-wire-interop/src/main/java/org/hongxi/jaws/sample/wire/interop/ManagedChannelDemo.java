package org.hongxi.jaws.sample.wire.interop;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.wire.LoadBalancePolicy;
import org.hongxi.jaws.wire.ManagedChannel;
import org.hongxi.jaws.wire.WireClientCall;
import org.hongxi.jaws.wire.WireClientCallHandler;
import org.hongxi.jaws.wire.WireClientInterceptor;
import org.hongxi.jaws.wire.WireCallOptions;
import org.hongxi.jaws.wire.WireConnectivityState;

import org.hongxi.jaws.rpc.Response;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates {@link ManagedChannel} load-balancing calls across multiple
 * external grpc-java servers, analogous to grpc-java's {@code ManagedChannel}
 * with {@code RoundRobinLoadBalancer} or {@code PickFirstLoadBalancer}.
 * <p>
 * The demo starts three grpc-java servers on different ports, creates a
 * {@link ManagedChannel} with all three addresses, and issues multiple unary
 * calls to show that requests are distributed across the backends.
 * <p>
 * Two load balance policies are demonstrated:
 * <ul>
 *   <li>{@link LoadBalancePolicy#ROUND_ROBIN} — calls cycle
 *       through all three servers evenly</li>
 *   <li>{@link LoadBalancePolicy#PICK_FIRST} — calls stick to
 *       the first available server</li>
 * </ul>
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.ManagedChannelLoadBalanceDemo"
 * </pre>
 *
 * @author shenhongxi
 * @see ManagedChannel
 */
public class ManagedChannelDemo {

    private static final int[] PORTS = {50061, 50062, 50063};

    private static final Metadata.Key<String> AUTH_TOKEN_KEY =
            Metadata.Key.of("x-auth-token", Metadata.ASCII_STRING_MARSHALLER);

    /** gRPC Context key for passing observed metadata from the server interceptor to the handler. */
    private static final io.grpc.Context.Key<String> RECEIVED_META_CTX =
            io.grpc.Context.key("receivedMeta");

    public static void main(String[] args) throws Exception {
        // Start three grpc-java servers, each identifying itself in responses.
        // A ServerInterceptor echoes received metadata into the response so we
        // can verify that ManagedChannel client interceptors propagated it.
        io.grpc.Server[] servers = new io.grpc.Server[PORTS.length];
        for (int i = 0; i < PORTS.length; i++) {
            final String serverId = "server-" + (char) ('A' + i) + ":" + PORTS[i];
            servers[i] = io.grpc.ServerBuilder.forPort(PORTS[i])
                    .addService(new GreeterGrpc.GreeterImplBase() {
                        @Override
                        public void sayHello(HelloRequest request,
                                             StreamObserver<HelloReply> responseObserver) {
                            String receivedMeta = RECEIVED_META_CTX.get();
                            System.out.println("[" + serverId + "] Received: " + request.getName()
                                    + (receivedMeta != null ? " [meta=" + receivedMeta + "]" : ""));
                            String reply = "Hello, " + request.getName() + "! (from " + serverId + ")";
                            if (receivedMeta != null) {
                                reply += " [server-saw: " + receivedMeta + "]";
                            }
                            responseObserver.onNext(HelloReply.newBuilder().setMessage(reply).build());
                            responseObserver.onCompleted();
                        }
                    })
                    .intercept(new ServerInterceptor() {
                        @Override
                        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                                ServerCall<ReqT, RespT> call, Metadata headers,
                                ServerCallHandler<ReqT, RespT> next) {
                            StringBuilder meta = new StringBuilder();
                            String token = headers.get(AUTH_TOKEN_KEY);
                            if (token != null) {
                                meta.append("x-auth-token=").append(token);
                            }
                            io.grpc.Context ctx = io.grpc.Context.current()
                                    .withValue(RECEIVED_META_CTX, meta.isEmpty() ? null : meta.toString());
                            return io.grpc.Contexts.interceptCall(ctx, call, headers, next);
                        }
                    })
                    .build()
                    .start();
            System.out.println("grpc-java " + serverId + " started");
        }

        try {
            // ---- 1. Round-Robin ----
            System.out.println("\n=== 1. Round-Robin Load Balancing ===");
            try (ManagedChannel channel = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + PORTS[0])
                    .addAddress("127.0.0.1:" + PORTS[1])
                    .addAddress("127.0.0.1:" + PORTS[2])
                    .roundRobin()
                    .requestTimeout(5000)
                    .build()) {

                System.out.println("ManagedChannel created with " + channel.size() + " backends, policy=ROUND_ROBIN");

                // Issue 6 calls — should distribute evenly across 3 servers
                for (int i = 1; i <= 6; i++) {
                    Response response = channel.unaryCall(
                            "interop.Greeter", "SayHello",
                            HelloRequest.newBuilder().setName("call-" + i).build(),
                            HelloReply.parser());
                    HelloReply reply = (HelloReply) response.getValue();
                    System.out.println("  Call " + i + " -> " + reply.getMessage());
                }
            }

            // ---- 2. Pick-First ----
            System.out.println("\n=== 2. Pick-First Load Balancing ===");
            try (ManagedChannel channel = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + PORTS[0])
                    .addAddress("127.0.0.1:" + PORTS[1])
                    .addAddress("127.0.0.1:" + PORTS[2])
                    .pickFirst()
                    .requestTimeout(5000)
                    .build()) {

                System.out.println("ManagedChannel created with " + channel.size() + " backends, policy=PICK_FIRST");

                // All calls should go to the first available server
                for (int i = 1; i <= 3; i++) {
                    Response response = channel.unaryCall(
                            "interop.Greeter", "SayHello",
                            HelloRequest.newBuilder().setName("pick-" + i).build(),
                            HelloReply.parser());
                    HelloReply reply = (HelloReply) response.getValue();
                    System.out.println("  Call " + i + " -> " + reply.getMessage());
                }
            }

            // ---- 3. Round-Robin with metadata ----
            System.out.println("\n=== 3. Round-Robin with Metadata ===");
            try (ManagedChannel channel = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + PORTS[0])
                    .addAddress("127.0.0.1:" + PORTS[1])
                    .addAddress("127.0.0.1:" + PORTS[2])
                    .roundRobin()
                    .compression("gzip")
                    .build()) {

                Map<String, String> metadata = Map.of(
                        "x-trace-id", "lb-demo-trace-001",
                        "x-request-source", "ManagedChannelLoadBalanceDemo");

                Response response = channel.unaryCall(
                        "interop.Greeter", "SayHello",
                        HelloRequest.newBuilder().setName("metadata-user").build(),
                        HelloReply.parser(),
                        metadata);
                HelloReply reply = (HelloReply) response.getValue();
                System.out.println("  Response: " + reply.getMessage());
            }

            // ---- 4. Round-Robin with client interceptor ----
            System.out.println("\n=== 4. Round-Robin + Client Interceptor ===");
            try (ManagedChannel channel = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + PORTS[0])
                    .addAddress("127.0.0.1:" + PORTS[1])
                    .addAddress("127.0.0.1:" + PORTS[2])
                    .roundRobin()
                    .requestTimeout(5000)
                    // Client interceptor: auto-inject x-auth-token on every call
                    // to every backend, mirroring grpc-java's ManagedChannelBuilder.intercept()
                    .intercept((call, next) -> {
                        System.out.println("[AuthInjector] injecting x-auth-token for " + call.path());
                        call.putAttachment("x-auth-token", "lb-token-789");
                        return next.newCall(call);
                    })
                    .build()) {

                System.out.println("ManagedChannel with " + channel.size()
                        + " backends + AuthInjector interceptor");

                // Issue 3 calls — interceptor fires on each, load balancer distributes
                for (int i = 1; i <= 3; i++) {
                    Response response = channel.unaryCall(
                            "interop.Greeter", "SayHello",
                            HelloRequest.newBuilder().setName("intercepted-" + i).build(),
                            HelloReply.parser());
                    HelloReply reply = (HelloReply) response.getValue();
                    System.out.println("  Call " + i + " -> " + reply.getMessage());
                    if (!reply.getMessage().contains("x-auth-token=lb-token-789")) {
                        throw new AssertionError(
                                "expected token in server-saw metadata, got: " + reply.getMessage());
                    }
                }
                System.out.println("Interceptor fired on every call across all backends.");
            }

            // ---- 5. Keepalive + retry configured through the channel ----
            // P0 made these reachable via the builder (previously openClient only
            // forwarded timeouts/compression, silently leaving keepalive off and
            // retry unconfigurable). This exercises a live connection with the
            // client keepalive PING handler installed and retry armed, proving the
            // propagated settings don't break real traffic end-to-end.
            System.out.println("\n=== 5. Round-Robin + Keepalive + Retry ===");
            try (ManagedChannel channel = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + PORTS[0])
                    .addAddress("127.0.0.1:" + PORTS[1])
                    .addAddress("127.0.0.1:" + PORTS[2])
                    .roundRobin()
                    .requestTimeout(5000)
                    .keepAlive(2000, 10000)   // PING every 2s, ACK timeout 10s
                    .retry(3, 50, 1000, 200, 20)
                    .build()) {

                System.out.println("ManagedChannel created with keepAlive(2s)+retry(3) "
                        + "across " + channel.size() + " backends");

                for (int i = 1; i <= 3; i++) {
                    Response response = channel.unaryCall(
                            "interop.Greeter", "SayHello",
                            HelloRequest.newBuilder().setName("ka-" + i).build(),
                            HelloReply.parser());
                    HelloReply reply = (HelloReply) response.getValue();
                    System.out.println("  Call " + i + " -> " + reply.getMessage());
                }
                // Hold the connection open past one keepalive interval so the
                // client PING handler actually fires, then confirm calls still work.
                System.out.println("  Holding 3s to emit at least one keepalive PING...");
                Thread.sleep(3000);
                for (int i = 4; i <= 6; i++) {
                    Response response = channel.unaryCall(
                            "interop.Greeter", "SayHello",
                            HelloRequest.newBuilder().setName("ka-" + i).build(),
                            HelloReply.parser());
                    HelloReply reply = (HelloReply) response.getValue();
                    System.out.println("  Call " + i + " (post-keepalive) -> " + reply.getMessage());
                }
            }

            // ---- 6. Per-call options + graceful shutdown lifecycle ----
            // P1: unaryCall now takes a per-call WireCallOptions (deadline /
            // compressor), and the channel exposes shutdown()/awaitTermination().
            // Both are exercised against the live grpc-java backends.
            System.out.println("\n=== 6. Per-call deadline + graceful shutdown ===");
            ManagedChannel channel = ManagedChannel.builder()
                    .addAddress("127.0.0.1:" + PORTS[0])
                    .addAddress("127.0.0.1:" + PORTS[1])
                    .addAddress("127.0.0.1:" + PORTS[2])
                    .roundRobin()
                    .requestTimeout(5000)
                    .build();

            // Per-call options on a live call: a 3s deadline (tighter than the 5s
            // channel timeout) plus metadata, proving the overload threads through.
            Response optsResponse = channel.unaryCall(
                    "interop.Greeter", "SayHello",
                    HelloRequest.newBuilder().setName("opts-user").build(),
                    HelloReply.parser(),
                    Map.of("x-trace-id", "opts-demo"),
                    WireCallOptions.DEFAULT.withDeadlineMs(3000));
            System.out.println("  per-call options -> "
                    + ((HelloReply) optsResponse.getValue()).getMessage());

            // Graceful shutdown: new calls rejected, in-flight drained, await returns.
            channel.shutdown();
            System.out.println("  isShutdown=" + channel.isShutdown());
            boolean rejected;
            try {
                channel.unaryCall("interop.Greeter", "SayHello",
                        HelloRequest.newBuilder().setName("after-shutdown").build(),
                        HelloReply.parser());
                rejected = false;
            } catch (IllegalStateException e) {
                rejected = true;
            }
            System.out.println("  call-after-shutdown rejected=" + rejected
                    + " (expected true)");
            boolean terminated = channel.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("  awaitTermination=" + terminated
                    + ", isTerminated=" + channel.isTerminated());
            if (!terminated || !channel.isTerminated() || !rejected) {
                throw new AssertionError(
                        "graceful shutdown lifecycle did not behave as expected");
            }

            // ---- 7. Pluggable resolution + load balancing + connectivity view (P2) ----
            // The target is resolved through NameResolverRegistry (passthrough
            // provider) and the balancer selected through LoadBalancerRegistry by
            // name, and the channel exposes an aggregate connectivity view.
            System.out.println("\n=== 7. Registry-resolved target + connectivity state ===");
            ManagedChannel p2 = ManagedChannel.builder()
                    .target("passthrough:///127.0.0.1:" + PORTS[0])   // via NameResolverRegistry
                    .loadBalancer("round_robin")                      // via LoadBalancerRegistry
                    .build();

            Response p2Response = p2.unaryCall("interop.Greeter", "SayHello",
                    HelloRequest.newBuilder().setName("p2-user").build(),
                    HelloReply.parser());
            System.out.println("  passthrough-target call -> "
                    + ((HelloReply) p2Response.getValue()).getMessage());
            System.out.println("  getState = " + p2.getState());
            if (p2.getState() != WireConnectivityState.READY) {
                throw new AssertionError("connected channel should report READY, got " + p2.getState());
            }

            AtomicInteger stateFired = new AtomicInteger();
            p2.notifyWhenStateChanged(WireConnectivityState.READY, stateFired::incrementAndGet);
            p2.shutdown();
            p2.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("  state-change callback fired=" + stateFired.get()
                    + ", getState after shutdown=" + p2.getState());
            if (stateFired.get() != 1 || p2.getState() != WireConnectivityState.SHUTDOWN) {
                throw new AssertionError("P2 connectivity view did not behave as expected");
            }

            System.out.println("\n=== ManagedChannel Load Balancing Demo Passed ===");
        } finally {
            for (io.grpc.Server server : servers) {
                server.shutdown();
            }
        }
    }
}
