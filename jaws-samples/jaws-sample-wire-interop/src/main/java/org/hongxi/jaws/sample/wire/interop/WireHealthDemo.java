package org.hongxi.jaws.sample.wire.interop;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireServer;
import org.hongxi.jaws.wire.WireHandlerRegistry;

import java.util.Map;

/**
 * Proves that a standard grpc-java client can call the jaws-wire server's
 * auto-registered {@code grpc.health.v1.Health} service:
 * <ol>
 *   <li>Start a {@link WireServer} (health check is auto-registered by the
 *       constructor, no manual setup needed)</li>
 *   <li>Create a grpc-java {@link ManagedChannel} and call
 *       {@code Health/Check} via {@link HealthGrpc.HealthBlockingStub}</li>
 *   <li>Verify the response is {@code SERVING}</li>
 * </ol>
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.WireHealthDemo"
 * </pre>
 */
public class WireHealthDemo {

    private static final int WIRE_PORT = 50062;

    public static void main(String[] args) throws Exception {
        // Start jaws-wire server; health service is auto-registered
        URL url = new URL("wire", "localhost", WIRE_PORT, "health", Map.of());
        WireServer wireServer = new WireServer(url, new WireHandlerRegistry());
        wireServer.open();
        System.out.println("jaws-wire server started on port " + WIRE_PORT
                + " (auto-registered grpc.health.v1.Health)");

        try {
            ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", WIRE_PORT)
                    .usePlaintext()
                    .build();
            try {
                System.out.println("\n=== Health Check (grpc-java client -> jaws-wire) ===");
                HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(channel);
                HealthCheckResponse healthResponse = healthStub.check(
                        HealthCheckRequest.newBuilder().build());
                System.out.println("Overall health status: " + healthResponse.getStatus());

                if (healthResponse.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
                    throw new AssertionError("expected SERVING, got: " + healthResponse.getStatus());
                }
                System.out.println("Health check passed!");
            } finally {
                channel.shutdown();
            }

            System.out.println("\n=== Health Check Interop Passed ===");
        } finally {
            wireServer.close();
        }

        System.exit(0);
    }
}
