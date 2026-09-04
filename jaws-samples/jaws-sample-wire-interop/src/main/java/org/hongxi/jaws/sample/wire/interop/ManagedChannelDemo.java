package org.hongxi.jaws.sample.wire.interop;

import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.wire.LoadBalancePolicy;
import org.hongxi.jaws.wire.ManagedChannel;

import org.hongxi.jaws.rpc.Response;

import java.util.Map;

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

    public static void main(String[] args) throws Exception {
        // Start three grpc-java servers, each identifying itself in responses
        io.grpc.Server[] servers = new io.grpc.Server[PORTS.length];
        for (int i = 0; i < PORTS.length; i++) {
            final String serverId = "server-" + (char) ('A' + i) + ":" + PORTS[i];
            servers[i] = io.grpc.ServerBuilder.forPort(PORTS[i])
                    .addService(new GreeterGrpc.GreeterImplBase() {
                        @Override
                        public void sayHello(HelloRequest request,
                                             StreamObserver<HelloReply> responseObserver) {
                            System.out.println("[" + serverId + "] Received: " + request.getName());
                            HelloReply reply = HelloReply.newBuilder()
                                    .setMessage("Hello, " + request.getName() + "! (from " + serverId + ")")
                                    .build();
                            responseObserver.onNext(reply);
                            responseObserver.onCompleted();
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

            System.out.println("\n=== ManagedChannel Load Balancing Demo Passed ===");
        } finally {
            for (io.grpc.Server server : servers) {
                server.shutdown();
            }
        }

        System.exit(0);
    }
}
