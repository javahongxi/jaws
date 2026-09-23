package org.hongxi.jaws.sample.wire.interop;

import com.google.protobuf.Message;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the {@link WireClientInterceptor} chain on {@link WireClient}.
 * <p>
 * Two client interceptors are registered on the {@link WireClient}:
 * <ol>
 *   <li><b>ClientLoggingInterceptor</b> (outermost): logs every outbound
 *       call's path and wraps the {@link WireClientCall} to observe the
 *       actual send without modifying the request.</li>
 *   <li><b>AuthInjectorInterceptor</b> (innermost): automatically injects
 *       an {@code x-auth-token} into the call context so the server can
 *       authenticate the caller. This simulates a common pattern where
 *       credentials are attached transparently by the client framework.</li>
 * </ol>
 * <p>
 * A self-contained grpc-java server is started on port 50066 with a
 * {@link ServerInterceptor} that echoes received metadata into the response,
 * making the interceptor effect visible.
 * <p>
 * Three scenarios are exercised:
 * <ul>
 *   <li>Basic call → AuthInjector auto-injects token, server confirms it</li>
 *   <li>Call with {@code x-trace-id} attachment → both token and trace-id
 *       arrive at the server</li>
 *   <li>Call without AuthInjector → a fresh WireClient without interceptors
 *       shows the server receives no token</li>
 * </ul>
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.WireClientInterceptorDemo"
 * </pre>
 */
public class WireClientInterceptorDemo {

    private static final int GRPC_PORT = 50066;

    private static final Metadata.Key<String> AUTH_TOKEN_KEY =
            Metadata.Key.of("x-auth-token", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACE_ID_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    /** gRPC Context key for passing observed metadata from the server interceptor to the handler. */
    private static final io.grpc.Context.Key<String> RECEIVED_META_CTX =
            io.grpc.Context.key("receivedMeta");

    public static void main(String[] args) throws Exception {
        // ---- Start a self-contained grpc-java server ----
        // Its ServerInterceptor echoes received metadata into the response message
        // so we can verify that WireClient interceptors propagated them correctly.
        Server grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new GreeterGrpc.GreeterImplBase() {
                    @Override
                    public void sayHello(HelloRequest request,
                                         io.grpc.stub.StreamObserver<HelloReply> responseObserver) {
                        String receivedMeta = RECEIVED_META_CTX.get();
                        String reply = "Hello, " + request.getName() + "!";
                        if (receivedMeta != null) {
                            reply += " [server-saw: " + receivedMeta + "]";
                        } else {
                            reply += " [server-saw: no metadata]";
                        }
                        System.out.println("[grpc-java server] " + reply);
                        responseObserver.onNext(HelloReply.newBuilder().setMessage(reply).build());
                        responseObserver.onCompleted();
                    }

                    @Override
                    public void sayHelloStream(HelloRequest request,
                                               io.grpc.stub.StreamObserver<HelloReply> responseObserver) {
                        String receivedMeta = RECEIVED_META_CTX.get();
                        String suffix = receivedMeta != null ? " [server-saw: " + receivedMeta + "]" : " [server-saw: no metadata]";
                        for (int i = 1; i <= 3; i++) {
                            String msg = "Hello #" + i + ", " + request.getName() + "!" + suffix;
                            System.out.println("[grpc-java server] Streaming: " + msg);
                            responseObserver.onNext(HelloReply.newBuilder().setMessage(msg).build());
                        }
                        responseObserver.onCompleted();
                    }

                    @Override
                    public io.grpc.stub.StreamObserver<HelloRequest> clientStreamGreet(io.grpc.stub.StreamObserver<HelloReply> responseObserver) {
                        return new io.grpc.stub.StreamObserver<>() {
                            private int count = 0;
                            private String lastMeta = null;

                            @Override
                            public void onNext(HelloRequest value) {
                                count++;
                                lastMeta = RECEIVED_META_CTX.get();
                                System.out.println("[grpc-java server] Client stream item #" + count + ": " + value.getName()
                                        + (lastMeta != null ? " [meta=" + lastMeta + "]" : ""));
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("[grpc-java server] Client stream error: " + t.getMessage());
                                responseObserver.onError(t);
                            }

                            @Override
                            public void onCompleted() {
                                String suffix = lastMeta != null ? " [server-saw: " + lastMeta + "]" : " [server-saw: no metadata]";
                                String reply = "Received " + count + " items." + suffix;
                                System.out.println("[grpc-java server] Client stream completed: " + reply);
                                responseObserver.onNext(HelloReply.newBuilder().setMessage(reply).build());
                                responseObserver.onCompleted();
                            }
                        };
                    }

                    @Override
                    public io.grpc.stub.StreamObserver<HelloRequest> bidiGreet(io.grpc.stub.StreamObserver<HelloReply> responseObserver) {
                        return new io.grpc.stub.StreamObserver<>() {
                            @Override
                            public void onNext(HelloRequest value) {
                                String receivedMeta = RECEIVED_META_CTX.get();
                                String suffix = receivedMeta != null ? " [server-saw: " + receivedMeta + "]" : " [server-saw: no metadata]";
                                String reply = "Echo: " + value.getName() + suffix;
                                System.out.println("[grpc-java server] Bidi echo: " + reply);
                                responseObserver.onNext(HelloReply.newBuilder().setMessage(reply).build());
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("[grpc-java server] Bidi stream error: " + t.getMessage());
                                responseObserver.onError(t);
                            }

                            @Override
                            public void onCompleted() {
                                System.out.println("[grpc-java server] Bidi stream completed");
                                responseObserver.onCompleted();
                            }
                        };
                    }
                })
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call, Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        // Collect interesting headers into a readable string
                        StringBuilder meta = new StringBuilder();
                        String token = headers.get(AUTH_TOKEN_KEY);
                        if (token != null) {
                            meta.append("x-auth-token=").append(token);
                        }
                        String traceId = headers.get(TRACE_ID_KEY);
                        if (traceId != null) {
                            if (!meta.isEmpty()) meta.append(", ");
                            meta.append("x-trace-id=").append(traceId);
                        }
                        System.out.println("[grpc-java server] Received metadata: "
                                + (meta.isEmpty() ? "(none)" : meta));

                        // Stash into Context so the handler can echo it in the response
                        io.grpc.Context ctx = io.grpc.Context.current()
                                .withValue(RECEIVED_META_CTX, meta.isEmpty() ? null : meta.toString());
                        return io.grpc.Contexts.interceptCall(ctx, call, headers, next);
                    }
                })
                .build()
                .start();
        System.out.println("grpc-java server started on port " + GRPC_PORT);

        try {
            // ---- Create WireClient with interceptors ----
            WireClient wireClient = new WireClient(buildUrl(Map.of(
                    "connectTimeout", "5000", "requestTimeout", "5000")));

            // 1. Logging interceptor: logs path, wraps call to observe send
            wireClient.addInterceptor(new ClientLoggingInterceptor());

            // 2. Auth injector: automatically attaches x-auth-token
            wireClient.addInterceptor(new AuthInjectorInterceptor("secret-token-123"));

            wireClient.open();

            try {
                // ---- 1. Basic call: AuthInjector auto-injects token ----
                System.out.println("\n=== 1. Basic Call (auto-inject token) ===");
                DefaultRequest request1 = new DefaultRequest();
                request1.setInterfaceName("interop.Greeter");
                request1.setMethodName("SayHello");
                request1.setArguments(new Object[]{
                        HelloRequest.newBuilder().setName("alice").build()
                });
                Response response1 = wireClient.request(request1, HelloReply.parser());
                HelloReply reply1 = (HelloReply) response1.getValue();
                System.out.println("Response: " + reply1.getMessage());
                if (!reply1.getMessage().contains("x-auth-token=secret-token-123")) {
                    throw new AssertionError(
                            "expected token in server-saw metadata, got: " + reply1.getMessage());
                }

                // ---- 2. Call with x-trace-id attachment ----
                System.out.println("\n=== 2. Call With Trace-ID Attachment ===");
                DefaultRequest request2 = new DefaultRequest();
                request2.setInterfaceName("interop.Greeter");
                request2.setMethodName("SayHello");
                request2.setArguments(new Object[]{
                        HelloRequest.newBuilder().setName("bob").build()
                });
                request2.setAttachment("x-trace-id", "trace-wire-456");
                Response response2 = wireClient.request(request2, HelloReply.parser());
                HelloReply reply2 = (HelloReply) response2.getValue();
                System.out.println("Response: " + reply2.getMessage());
                if (!reply2.getMessage().contains("x-auth-token=secret-token-123")
                        || !reply2.getMessage().contains("x-trace-id=trace-wire-456")) {
                    throw new AssertionError(
                            "expected both token and trace-id, got: " + reply2.getMessage());
                }
                System.out.println("Both interceptor-injected and request-set metadata arrived.");

                // ---- 3. Call WITHOUT interceptors (fresh client) ----
                System.out.println("\n=== 3. Call Without Interceptors (baseline) ===");
                WireClient plainClient = new WireClient(buildUrl(Map.of(
                        "connectTimeout", "5000", "requestTimeout", "5000")));
                plainClient.open();
                try {
                    DefaultRequest request3 = new DefaultRequest();
                    request3.setInterfaceName("interop.Greeter");
                    request3.setMethodName("SayHello");
                    request3.setArguments(new Object[]{
                            HelloRequest.newBuilder().setName("charlie").build()
                    });
                    Response response3 = plainClient.request(request3, HelloReply.parser());
                    HelloReply reply3 = (HelloReply) response3.getValue();
                    System.out.println("Response: " + reply3.getMessage());
                    if (!reply3.getMessage().contains("no metadata")) {
                        throw new AssertionError(
                                "expected 'no metadata', got: " + reply3.getMessage());
                    }
                } finally {
                    plainClient.close();
                }

                // ---- 4. Server-streaming call with interceptors ----
                System.out.println("\n=== 4. Server-Streaming Call (interceptors apply) ===");
                DefaultRequest streamRequest = new DefaultRequest();
                streamRequest.setInterfaceName("interop.Greeter");
                streamRequest.setMethodName("SayHelloStream");
                streamRequest.setArguments(new Object[]{
                        HelloRequest.newBuilder().setName("stream-user").build()
                });
                CountDownLatch streamLatch = new CountDownLatch(1);
                int[] itemCount = {0};
                org.hongxi.jaws.stream.StreamSource<Object> streamSource = wireClient.requestStream(streamRequest, HelloReply.parser());
                streamSource.subscribe(new org.hongxi.jaws.stream.StreamObserver<>() {
                    @Override
                    public void onNext(Object item) {
                        itemCount[0]++;
                        HelloReply reply = (HelloReply) item;
                        System.out.println("  Stream item #" + itemCount[0] + ": " + reply.getMessage());
                        if (!reply.getMessage().contains("x-auth-token=secret-token-123")) {
                            throw new AssertionError(
                                    "expected token in streaming response, got: " + reply.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        System.err.println("  Stream error: " + throwable.getMessage());
                        streamLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("  Stream completed (" + itemCount[0] + " items)");
                        streamLatch.countDown();
                    }
                });
                if (!streamLatch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Server-streaming call timed out");
                }
                if (itemCount[0] != 3) {
                    throw new AssertionError("Expected 3 stream items, got: " + itemCount[0]);
                }
                System.out.println("Interceptors fired for server-streaming call.");

                // ---- 5. Client-streaming call with interceptors ----
                System.out.println("\n=== 5. Client-Streaming Call (interceptors apply) ===");
                DefaultRequest clientStreamRequest = new DefaultRequest();
                clientStreamRequest.setInterfaceName("interop.Greeter");
                clientStreamRequest.setMethodName("ClientStreamGreet");
                clientStreamRequest.setArguments(new Object[0]);
                org.hongxi.jaws.transport.StreamSubject<Object> clientStreamObserver = new org.hongxi.jaws.transport.StreamSubject<>();
                CountDownLatch clientStreamLatch = new CountDownLatch(1);
                org.hongxi.jaws.stream.StreamSource<Object> clientStreamResponse = wireClient.requestStream(
                        clientStreamRequest, clientStreamObserver, HelloReply.parser());
                clientStreamResponse.subscribe(new org.hongxi.jaws.stream.StreamObserver<>() {
                    @Override
                    public void onNext(Object item) {
                        HelloReply reply = (HelloReply) item;
                        System.out.println("  Client-stream response: " + reply.getMessage());
                        if (!reply.getMessage().contains("x-auth-token=secret-token-123")) {
                            throw new AssertionError(
                                    "expected token in client-streaming response, got: " + reply.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        System.err.println("  Client-stream error: " + throwable.getMessage());
                        clientStreamLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("  Client-stream completed");
                        clientStreamLatch.countDown();
                    }
                });
                // Send items
                Thread.sleep(100);
                clientStreamObserver.onNext(HelloRequest.newBuilder().setName("Alice").build());
                Thread.sleep(100);
                clientStreamObserver.onNext(HelloRequest.newBuilder().setName("Bob").build());
                Thread.sleep(100);
                clientStreamObserver.onCompleted();
                if (!clientStreamLatch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Client-streaming call timed out");
                }
                System.out.println("Interceptors fired for client-streaming call.");

                // ---- 6. Bidi-streaming call with interceptors ----
                System.out.println("\n=== 6. Bidi-Streaming Call (interceptors apply) ===");
                DefaultRequest bidiRequest = new DefaultRequest();
                bidiRequest.setInterfaceName("interop.Greeter");
                bidiRequest.setMethodName("BidiGreet");
                bidiRequest.setArguments(new Object[0]);
                org.hongxi.jaws.transport.StreamSubject<Object> bidiRequestObserver = new org.hongxi.jaws.transport.StreamSubject<>();
                CountDownLatch bidiLatch = new CountDownLatch(1);
                int[] bidiCount = {0};
                org.hongxi.jaws.stream.StreamSource<Object> bidiResponse = wireClient.requestBidiStream(
                        bidiRequest, bidiRequestObserver, HelloReply.parser());
                bidiResponse.subscribe(new org.hongxi.jaws.stream.StreamObserver<>() {
                    @Override
                    public void onNext(Object item) {
                        bidiCount[0]++;
                        HelloReply reply = (HelloReply) item;
                        System.out.println("  Bidi response #" + bidiCount[0] + ": " + reply.getMessage());
                        if (!reply.getMessage().contains("x-auth-token=secret-token-123")) {
                            throw new AssertionError(
                                    "expected token in bidi-streaming response, got: " + reply.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        System.err.println("  Bidi-stream error: " + throwable.getMessage());
                        bidiLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("  Bidi-stream completed (" + bidiCount[0] + " items)");
                        bidiLatch.countDown();
                    }
                });
                // Send items
                Thread.sleep(100);
                bidiRequestObserver.onNext(HelloRequest.newBuilder().setName("X").build());
                Thread.sleep(100);
                bidiRequestObserver.onNext(HelloRequest.newBuilder().setName("Y").build());
                Thread.sleep(100);
                bidiRequestObserver.onCompleted();
                if (!bidiLatch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Bidi-streaming call timed out");
                }
                if (bidiCount[0] != 2) {
                    throw new AssertionError("Expected 2 bidi responses, got: " + bidiCount[0]);
                }
                System.out.println("Interceptors fired for bidi-streaming call.");

                System.out.println("\n=== WireClientInterceptor Demo Passed (all modes) ===");
            } finally {
                wireClient.close();
            }
        } finally {
            grpcServer.shutdown();
        }
    }

    private static URL buildUrl(Map<String, String> extraParams) {
        Map<String, String> params = new HashMap<>(extraParams);
        return new URL("grpc", "localhost", GRPC_PORT, "interop.Greeter", params);
    }

    // ---- Client interceptor implementations ----

    /**
     * Outermost client interceptor that logs every outbound call's path.
     * Wraps the {@link WireClientCall} (Forwarding pattern) to observe
     * the actual send without modifying the request.
     */
    private static class ClientLoggingInterceptor implements WireClientInterceptor {
        @Override
        public WireClientCall interceptCall(WireClientCall call, WireClientCallHandler next) {
            String path = call.path();
            System.out.println("[ClientLoggingInterceptor] >>> " + path);

            WireClientCall wrappedCall = new WireClientCall() {
                @Override
                public WireCallContext context() {
                    return call.context();
                }

                @Override
                public String path() {
                    return call.path();
                }

                @Override
                public void putAttachment(String key, String value) {
                    call.putAttachment(key, value);
                }

                @Override
                public void sendMessage(Message request) {
                    System.out.println("[ClientLoggingInterceptor] sending to " + path);
                    call.sendMessage(request);
                }

                @Override
                public void cancel(String reason) {
                    System.out.println("[ClientLoggingInterceptor] cancel: " + reason);
                    call.cancel(reason);
                }
            };

            return next.newCall(wrappedCall);
        }
    }

    /**
     * Innermost client interceptor that automatically injects an
     * {@code x-auth-token} into the call context. The token is configured
     * at construction time, simulating a credential provider pattern.
     */
    private static class AuthInjectorInterceptor implements WireClientInterceptor {

        private final String token;

        AuthInjectorInterceptor(String token) {
            this.token = token;
        }

        @Override
        public WireClientCall interceptCall(WireClientCall call, WireClientCallHandler next) {
            System.out.println("[AuthInjectorInterceptor] injecting x-auth-token");
            call.putAttachment("x-auth-token", token);
            return next.newCall(call);
        }
    }
}
