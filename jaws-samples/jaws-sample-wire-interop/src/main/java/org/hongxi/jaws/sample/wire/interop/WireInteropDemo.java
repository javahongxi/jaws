package org.hongxi.jaws.sample.wire.interop;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Proves that {@link WireClient} (jaws-wire, zero grpc-java dependency) can
 * call a standard grpc-java server.
 * <p>
 * What this demo does:
 * <ol>
 *   <li>Starts a <b>standard grpc-java server</b> (using {@code GreeterGrpc.GreeterImplBase})
 *       on port 50060</li>
 *   <li>Creates a {@link WireClient} and sends a gRPC request to the server</li>
 *   <li>Prints the response to prove interoperability</li>
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

    public static void main(String[] args) throws Exception {
        // ---- Step 1: Start a standard grpc-java server ----
        Server grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new GreeterGrpc.GreeterImplBase() {
                    @Override
                    public void sayHello(HelloRequest request,
                                         StreamObserver<HelloReply> responseObserver) {
                        System.out.println("[grpc-java server] Received: " + request.getName());
                        HelloReply reply = HelloReply.newBuilder()
                                .setMessage("Hello, " + request.getName() + "! (from standard grpc-java)")
                                .build();
                        responseObserver.onNext(reply);
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        System.out.println("grpc-java server started on port " + GRPC_PORT);

        try {
            // ---- Step 2: Use WireClient (zero grpc-java dep) to call it ----
            Map<String, String> params = new HashMap<>();
            params.put("connectTimeout", "5000");
            params.put("requestTimeout", "5000");
            URL url = new URL("grpc", "localhost", GRPC_PORT, "interop.Greeter", params);

            WireClient wireClient = new WireClient(url);
            wireClient.open();
            System.out.println("WireClient connected to grpc-java server");

            // Build a request targeting /interop.Greeter/SayHello
            DefaultRequest request = new DefaultRequest();
            request.setInterfaceName("interop.Greeter");
            request.setMethodName("SayHello");
            request.setArguments(new Object[]{
                    HelloRequest.newBuilder().setName("jaws-wire").build()
            });

            // Call using the standard gRPC wire format
            Response response = wireClient.request(request, HelloReply.parser());
            HelloReply reply = (HelloReply) response.getValue();

            // ---- Step 3: Print result ----
            System.out.println();
            System.out.println("=== Interop Test Passed ===");
            System.out.println("WireClient (Netty HTTP/2, no grpc-java) -> grpc-java server");
            System.out.println("Response: " + reply.getMessage());
            System.out.println("===========================");

            wireClient.close();
        } finally {
            grpcServer.shutdown();
        }

        System.exit(0);
    }
}
