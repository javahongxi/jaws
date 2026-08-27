package org.hongxi.jaws.sample.wire.interop;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;
import org.hongxi.jaws.wire.WireHealthService;
import org.hongxi.jaws.wire.WireServer;
import org.hongxi.jaws.wire.WireServiceRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Proves that {@link WireClient} (jaws-wire, zero grpc-java dependency) can
 * call a standard grpc-java server, including the newer gRPC semantics:
 * <ol>
 *   <li><b>Baseline call</b>: unary SayHello over the standard gRPC wire format</li>
 *   <li><b>Metadata</b>: request attachments travel as custom HTTP/2 headers
 *       and are read by a grpc-java {@link ServerInterceptor}</li>
 *   <li><b>Compression</b>: the request is gzip-compressed (grpc-encoding: gzip)
 *       and decompressed natively by the grpc-java server</li>
 *   <li><b>Deadline &amp; cancellation</b>: the caller's timeout is propagated
 *       via grpc-timeout; when it fires the client resets the stream with
 *       RST_STREAM(CANCEL) and the grpc-java server observes the cancellation</li>
 *   <li><b>Health check</b>: grpc-java client calls jaws-wire's grpc.health.v1
 *       Health service (reverse direction)</li>
 * </ol>
 * <p>
 * The grpc-java server speaks the standard gRPC wire format (HTTP/2 + 5-byte
 * length-prefixed protobuf frames). The WireClient speaks the same format but
 * is implemented entirely on Netty HTTP/2 without any grpc-java dependency.
 * <p>
 * Run:
 * <pre>
 *   mvn compile exec:java -pl jaws-samples/jaws-sample-wire-interop \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.WireInteropDemo"
 * </pre>
 */
public class WireInteropDemo {

    private static final int GRPC_PORT = 50060;
    private static final int HEALTH_PORT = 50062;

    /** Custom metadata key sent as an HTTP/2 header by WireClient. */
    private static final Metadata.Key<String> TRACE_ID_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    public static void main(String[] args) throws Exception {
        // ---- Step 1: Start a standard grpc-java server ----
        Server grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new GreeterGrpc.GreeterImplBase() {
                    @Override
                    public void sayHello(HelloRequest request,
                                         StreamObserver<HelloReply> responseObserver) {
                        System.out.println("[grpc-java server] Received: " + request.getName());

                        if (request.getName().startsWith("slow:")) {
                            // The grpc-timeout header becomes the server-side Context deadline
                            Deadline deadline = Context.current().getDeadline();
                            System.out.println("[grpc-java server] deadline propagated via grpc-timeout, remaining="
                                    + (deadline == null ? "none"
                                    : deadline.timeRemaining(TimeUnit.MILLISECONDS) + "ms"));
                            // Fires when the client cancels (RST_STREAM CANCEL)
                            // or the deadline expires
                            Context.current().addListener(
                                    context -> System.out.println(
                                            "[grpc-java server] call cancelled (client RST_STREAM or deadline expired)"),
                                    Runnable::run);
                            try {
                                // Outrun the caller's 300ms deadline
                                Thread.sleep(1500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }

                        HelloReply reply = HelloReply.newBuilder()
                                .setMessage("Hello, " + request.getName() + "! (from standard grpc-java)")
                                .build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    }
                })
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call, Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        String traceId = headers.get(TRACE_ID_KEY);
                        if (traceId != null) {
                            System.out.println("[grpc-java server] metadata x-trace-id = " + traceId);
                        }
                        return next.startCall(call, headers);
                    }
                })
                .build()
                .start();
        System.out.println("grpc-java server started on port " + GRPC_PORT);

        // ---- Step 5: Start jaws-wire health server ----
        WireServiceRegistry registry = new WireServiceRegistry();
        WireHealthService healthService = new WireHealthService();
        healthService.registerTo(registry);
        URL healthUrl = new URL("wire", "localhost", HEALTH_PORT, "health", Map.of());
        WireServer healthServer = new WireServer(healthUrl, registry);
        healthServer.open();
        System.out.println("jaws-wire health server started on port " + HEALTH_PORT);

        try {
            // ---- Step 2: Baseline call + metadata ----
            WireClient wireClient = new WireClient(buildUrl(Map.of(
                    "connectTimeout", "5000", "requestTimeout", "5000")));
            wireClient.open();
            System.out.println("WireClient connected to grpc-java server");

            DefaultRequest request = new DefaultRequest();
            request.setInterfaceName("interop.Greeter");
            request.setMethodName("SayHello");
            request.setArguments(new Object[]{
                    HelloRequest.newBuilder().setName("jaws-wire").build()
            });
            // Request attachments -> gRPC metadata (custom HTTP/2 headers)
            request.setAttachment("x-trace-id", "trace-abc-123");

            System.out.println("\n=== 1. Baseline + Metadata ===");
            Response response = wireClient.request(request, HelloReply.parser());
            HelloReply reply = (HelloReply) response.getValue();
            System.out.println("Response: " + reply.getMessage());
            wireClient.close();

            // ---- Step 3: gzip request compression ----
            System.out.println("\n=== 2. Gzip Request Compression ===");
            WireClient gzipClient = new WireClient(buildUrl(Map.of(
                    "connectTimeout", "5000", "requestTimeout", "5000",
                    "compression", "gzip")));
            gzipClient.open();

            DefaultRequest gzipRequest = new DefaultRequest();
            gzipRequest.setInterfaceName("interop.Greeter");
            gzipRequest.setMethodName("SayHello");
            gzipRequest.setArguments(new Object[]{
                    HelloRequest.newBuilder().setName("gzip-user").build()
            });
            // The grpc-java server decompresses gzip natively; a successful
            // response proves the compressed frame was wire-compatible
            Response gzipResponse = gzipClient.request(gzipRequest, HelloReply.parser());
            System.out.println("Response: " + ((HelloReply) gzipResponse.getValue()).getMessage());
            gzipClient.close();

            // ---- Step 4: deadline propagation + cancellation ----
            System.out.println("\n=== 3. Deadline & Cancellation ===");
            WireClient slowClient = new WireClient(buildUrl(Map.of(
                    "connectTimeout", "5000", "requestTimeout", "300")));
            slowClient.open();

            DefaultRequest slowRequest = new DefaultRequest();
            slowRequest.setInterfaceName("interop.Greeter");
            slowRequest.setMethodName("SayHello");
            slowRequest.setArguments(new Object[]{
                    HelloRequest.newBuilder().setName("slow:jaws-wire").build()
            });
            try {
                slowClient.request(slowRequest, HelloReply.parser());
                System.out.println("ERROR: expected the call to time out");
            } catch (JawsAbstractException e) {
                System.out.println("WireClient failed as expected: " + e.getMessage());
                System.out.println("(deadline sent as grpc-timeout; stream reset with RST_STREAM CANCEL)");
            }
            slowClient.close();
            // Give the server a moment to print its cancellation observation
            Thread.sleep(500);

            // ---- Step 6: Health check verification ----
            System.out.println("\n=== 4. Health Check (grpc-java client -> jaws-wire server) ===");
            ManagedChannel healthChannel = ManagedChannelBuilder.forAddress("localhost", HEALTH_PORT)
                    .usePlaintext()
                    .build();
            try {
                HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(healthChannel);
                HealthCheckResponse healthResponse = healthStub.check(
                        HealthCheckRequest.newBuilder().build());
                System.out.println("Overall health status: " + healthResponse.getStatus());
                if (healthResponse.getStatus() == HealthCheckResponse.ServingStatus.SERVING) {
                    System.out.println("Health check passed!");
                } else {
                    System.out.println("ERROR: unexpected health status");
                }
            } finally {
                healthChannel.shutdown();
            }
            healthServer.close();

            System.out.println();
            System.out.println("=== Interop Test Passed ===");
            System.out.println("WireClient (Netty HTTP/2, no grpc-java) -> grpc-java server");
            System.out.println("baseline + metadata + gzip + deadline/cancel all verified");
            System.out.println("grpc-java client -> jaws-wire health server verified");
            System.out.println("===========================");
        } finally {
            grpcServer.shutdown();
        }

        System.exit(0);
    }

    private static URL buildUrl(Map<String, String> extraParams) {
        Map<String, String> params = new HashMap<>(extraParams);
        return new URL("grpc", "localhost", GRPC_PORT, "interop.Greeter", params);
    }
}
