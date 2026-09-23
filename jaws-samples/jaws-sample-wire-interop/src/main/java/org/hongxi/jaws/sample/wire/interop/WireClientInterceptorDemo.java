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
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.transport.http2.Http2Constants;
import org.hongxi.jaws.transport.http2.StreamType;
import org.hongxi.jaws.wire.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Six scenarios are exercised:
 * <ul>
 *   <li>Basic call → AuthInjector auto-injects token, server confirms it</li>
 *   <li>Call with {@code x-trace-id} attachment → both token and trace-id
 *       arrive at the server</li>
 *   <li>Call without AuthInjector → a fresh WireClient without interceptors
 *       shows the server receives no token</li>
 *   <li>Server-streaming → interceptor-injected token reaches the server and
 *       rides back on every streamed reply; the chain observes the single
 *       request send and the half-close</li>
 *   <li>Client-streaming → the chain observes <em>each</em> outbound request
 *       item and the trailing half-close, and all items aggregate into one
 *       server reply</li>
 *   <li>Bi-directional streaming → the chain observes each of the request
 *       items echoed concurrently with the response stream</li>
 * </ul>
 * The three streaming scenarios are the point of interest: unlike a metadata-
 * only interceptor, the chain here sees <em>every</em> outbound message, because
 * streaming routes each item through the wrapped {@link WireClientCall}.
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
                        // Server-streaming handler runs inside the intercepted call
                        // context, so the metadata the client interceptor injected is
                        // visible here and rides back on every streamed reply.
                        String receivedMeta = RECEIVED_META_CTX.get();
                        String suffix = receivedMeta != null
                                ? " [server-saw: " + receivedMeta + "]" : " [server-saw: no metadata]";
                        for (int i = 1; i <= 3; i++) {
                            responseObserver.onNext(HelloReply.newBuilder()
                                    .setMessage("Hello #" + i + ", " + request.getName() + "!" + suffix)
                                    .build());
                        }
                        responseObserver.onCompleted();
                    }

                    @Override
                    public io.grpc.stub.StreamObserver<HelloRequest> clientStreamGreet(
                            io.grpc.stub.StreamObserver<HelloReply> responseObserver) {
                        List<String> names = new CopyOnWriteArrayList<>();
                        return new io.grpc.stub.StreamObserver<>() {
                            @Override
                            public void onNext(HelloRequest request) {
                                names.add(request.getName());
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("[grpc-java server] client-stream error: " + t.getMessage());
                            }

                            @Override
                            public void onCompleted() {
                                responseObserver.onNext(HelloReply.newBuilder()
                                        .setMessage("Hello, " + String.join(", ", names) + "!")
                                        .build());
                                responseObserver.onCompleted();
                            }
                        };
                    }

                    @Override
                    public io.grpc.stub.StreamObserver<HelloRequest> bidiGreet(
                            io.grpc.stub.StreamObserver<HelloReply> responseObserver) {
                        return new io.grpc.stub.StreamObserver<>() {
                            @Override
                            public void onNext(HelloRequest request) {
                                responseObserver.onNext(HelloReply.newBuilder()
                                        .setMessage("Hello, " + request.getName() + "!")
                                        .build());
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("[grpc-java server] bidi error: " + t.getMessage());
                            }

                            @Override
                            public void onCompleted() {
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

            // 3. Innermost tally: counts each outbound sendMessage and the
            //    half-close, so the streaming scenarios can assert the chain
            //    really sees every item (not just the opening metadata).
            StreamTallyInterceptor tally = new StreamTallyInterceptor();
            wireClient.addInterceptor(tally);

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

                System.out.println("\n=== 4. Server-Streaming (interceptor sees the request + half-close) ===");
                DefaultRequest serverStreamReq = new DefaultRequest();
                serverStreamReq.setInterfaceName("interop.Greeter");
                serverStreamReq.setMethodName("SayHelloStream");
                serverStreamReq.setArguments(new Object[]{
                        HelloRequest.newBuilder().setName("streamer").build()
                });
                CountDownLatch serverStreamDone = new CountDownLatch(1);
                AtomicInteger serverStreamItems = new AtomicInteger();
                AtomicBoolean tokenSeen = new AtomicBoolean();
                wireClient.requestStream(serverStreamReq, HelloReply.parser())
                        .subscribe(new StreamObserver<Object>() {
                            @Override
                            public void onNext(Object item) {
                                serverStreamItems.incrementAndGet();
                                HelloReply reply = (HelloReply) item;
                                System.out.println("  server-stream item: " + reply.getMessage());
                                if (reply.getMessage().contains("x-auth-token=secret-token-123")) {
                                    tokenSeen.set(true);
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("  server-stream error: " + t.getMessage());
                                serverStreamDone.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                serverStreamDone.countDown();
                            }
                        });
                await(serverStreamDone, "server-streaming");
                assertTrue(serverStreamItems.get() == 3,
                        "expected 3 server-stream items, got " + serverStreamItems.get());
                assertTrue(tokenSeen.get(), "interceptor token must ride back on server-stream replies");
                assertTrue(tally.sends.get() == 1 && tally.halfClosed.get(),
                        "chain must see the 1 request send and the half-close, saw sends="
                                + tally.sends.get() + " halfClosed=" + tally.halfClosed.get());

                System.out.println("\n=== 5. Client-Streaming (interceptor sees EVERY outbound item) ===");
                DefaultRequest clientStreamReq = new DefaultRequest();
                clientStreamReq.setInterfaceName("interop.Greeter");
                clientStreamReq.setMethodName("ClientStreamGreet");
                clientStreamReq.setArguments(new Object[0]);
                clientStreamReq.setAttachment(Http2Constants.HEADER_STREAMING, StreamType.CLIENT.getValue());
                StreamSubject<Object> clientOutbound = new StreamSubject<>();
                CountDownLatch clientStreamDone = new CountDownLatch(1);
                AtomicBoolean aggregated = new AtomicBoolean();
                wireClient.requestStream(clientStreamReq, clientOutbound, HelloReply.parser())
                        .subscribe(new StreamObserver<Object>() {
                            @Override
                            public void onNext(Object item) {
                                HelloReply reply = (HelloReply) item;
                                System.out.println("  client-stream response: " + reply.getMessage());
                                if (reply.getMessage().contains("Alice")
                                        && reply.getMessage().contains("Bob")
                                        && reply.getMessage().contains("Charlie")) {
                                    aggregated.set(true);
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("  client-stream error: " + t.getMessage());
                                clientStreamDone.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                clientStreamDone.countDown();
                            }
                        });
                clientOutbound.onNext(HelloRequest.newBuilder().setName("Alice").build());
                clientOutbound.onNext(HelloRequest.newBuilder().setName("Bob").build());
                clientOutbound.onNext(HelloRequest.newBuilder().setName("Charlie").build());
                clientOutbound.onCompleted();
                await(clientStreamDone, "client-streaming");
                assertTrue(aggregated.get(), "all 3 request items must aggregate into one server reply");
                assertTrue(tally.sends.get() == 3 && tally.halfClosed.get(),
                        "chain must see all 3 outbound items + half-close, saw sends="
                                + tally.sends.get() + " halfClosed=" + tally.halfClosed.get());

                System.out.println("\n=== 6. Bi-directional Streaming (interceptor sees each item both ways) ===");
                DefaultRequest bidiReq = new DefaultRequest();
                bidiReq.setInterfaceName("interop.Greeter");
                bidiReq.setMethodName("BidiGreet");
                bidiReq.setArguments(new Object[0]);
                StreamSubject<Object> bidiOutbound = new StreamSubject<>();
                CountDownLatch bidiDone = new CountDownLatch(1);
                AtomicInteger bidiItems = new AtomicInteger();
                wireClient.requestBidiStream(bidiReq, bidiOutbound, HelloReply.parser())
                        .subscribe(new StreamObserver<Object>() {
                            @Override
                            public void onNext(Object item) {
                                bidiItems.incrementAndGet();
                                System.out.println("  bidi response: " + ((HelloReply) item).getMessage());
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("  bidi error: " + t.getMessage());
                                bidiDone.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                bidiDone.countDown();
                            }
                        });
                bidiOutbound.onNext(HelloRequest.newBuilder().setName("Alice").build());
                bidiOutbound.onNext(HelloRequest.newBuilder().setName("Bob").build());
                bidiOutbound.onNext(HelloRequest.newBuilder().setName("Charlie").build());
                bidiOutbound.onCompleted();
                await(bidiDone, "bidi-streaming");
                assertTrue(bidiItems.get() == 3, "expected 3 bidi replies, got " + bidiItems.get());
                assertTrue(tally.sends.get() == 3 && tally.halfClosed.get(),
                        "chain must see all 3 outbound items + half-close, saw sends="
                                + tally.sends.get() + " halfClosed=" + tally.halfClosed.get());

                System.out.println("\n=== WireClientInterceptor Demo Passed ===");
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

            WireClientCall wrappedCall = new ForwardingClientCall(call) {
                @Override
                public void sendMessage(Message request) {
                    System.out.println("[ClientLoggingInterceptor] sending to " + path);
                    super.sendMessage(request);
                }

                @Override
                public void halfClose() {
                    System.out.println("[ClientLoggingInterceptor] half-close " + path);
                    super.halfClose();
                }

                @Override
                public void cancel(String reason) {
                    System.out.println("[ClientLoggingInterceptor] cancel: " + reason);
                    super.cancel(reason);
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

    /**
     * Innermost interceptor that tallies the outbound traffic of the most recent
     * call: one increment per {@code sendMessage} and a flag when {@code halfClose}
     * runs. It proves the streaming path routes every request item through the
     * interceptor chain, not just the opening metadata.
     */
    private static class StreamTallyInterceptor implements WireClientInterceptor {
        final AtomicInteger sends = new AtomicInteger();
        final AtomicBoolean halfClosed = new AtomicBoolean();

        @Override
        public WireClientCall interceptCall(WireClientCall call, WireClientCallHandler next) {
            sends.set(0);
            halfClosed.set(false);
            return next.newCall(new ForwardingClientCall(call) {
                @Override
                public void sendMessage(Message request) {
                    sends.incrementAndGet();
                    super.sendMessage(request);
                }

                @Override
                public void halfClose() {
                    halfClosed.set(true);
                    super.halfClose();
                }
            });
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(what + " call timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(what + " call interrupted", e);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
