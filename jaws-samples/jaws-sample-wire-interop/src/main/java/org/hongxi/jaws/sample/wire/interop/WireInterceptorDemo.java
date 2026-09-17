package org.hongxi.jaws.sample.wire.interop;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the {@link WireServerInterceptor} chain in jaws-wire Direct API mode.
 * <p>
 * Two interceptors are registered on the {@link WireHandlerRegistry}:
 * <ol>
 *   <li><b>LoggingInterceptor</b> (outermost): records every call's path, elapsed
 *       time, and gRPC status. Wraps the {@link WireServerCall} to observe the
 *       outbound close status without short-circuiting.</li>
 *   <li><b>AuthInterceptor</b> (innermost): checks for an {@code x-auth-token}
 *       metadata entry. If absent, short-circuits with {@code UNAUTHENTICATED};
 *       if present, injects a {@code x-user} attachment into the
 *       {@link WireCallContext} and delegates to the next handler.</li>
 * </ol>
 * <p>
 * A grpc-java client exercises three scenarios:
 * <ul>
 *   <li>Call without token → rejected by AuthInterceptor (UNAUTHENTICATED)</li>
 *   <li>Call with valid token → passes both interceptors, handler sees
 *       the {@code x-user} injected by AuthInterceptor</li>
 *   <li>Server-streaming call with token → interceptors also work for
 *       streaming call types</li>
 * </ul>
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.WireInterceptorDemo"
 * </pre>
 */
public class WireInterceptorDemo {

    private static final int WIRE_PORT = 50065;

    private static final Metadata.Key<String> AUTH_TOKEN_KEY =
            Metadata.Key.of("x-auth-token", Metadata.ASCII_STRING_MARSHALLER);

    public static void main(String[] args) throws Exception {
        WireHandlerRegistry registry = new WireHandlerRegistry();

        // ---- Register interceptors (first added = outermost) ----

        // 1. Logging interceptor: wraps the call to observe timing and status
        registry.addInterceptor(new LoggingInterceptor());

        // 2. Auth interceptor: checks x-auth-token, injects x-user on success
        registry.addInterceptor(new AuthInterceptor());

        // ---- Business handler: echoes the caller identity from WireCallContext ----
        registry.register("interop.Greeter", "SayHello", new WireMethodHandler() {
            @Override
            public Message handle(Message request, WireCallContext context) {
                HelloRequest req = (HelloRequest) request;
                String user = context.getAttachment("x-user");
                String reply = "Hello, " + req.getName() + "!";
                if (user != null) {
                    reply += " (authenticated as " + user + ")";
                }
                System.out.println("[handler] " + reply);
                return HelloReply.newBuilder().setMessage(reply).build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HelloRequest.parser();
            }
        });

        // ---- Server-streaming handler (also intercepted) ----
        registry.register("interop.Greeter", "SayHelloStream", new WireMethodHandler() {
            @Override
            public MethodType methodType() {
                return MethodType.SERVER_STREAM;
            }

            @Override
            public StreamSource<Message> handleStream(Message request, WireCallContext context) {
                HelloRequest req = (HelloRequest) request;
                String user = context.getAttachment("x-user");
                String suffix = user != null ? " [user=" + user + "]" : "";
                StreamSubject<Message> observer = new StreamSubject<>();
                for (int i = 1; i <= 3; i++) {
                    observer.onNext(HelloReply.newBuilder()
                            .setMessage("Hello #" + i + ", " + req.getName() + "!" + suffix)
                            .build());
                }
                observer.onCompleted();
                return observer;
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HelloRequest.parser();
            }
        });

        URL url = new URL("wire", "localhost", WIRE_PORT, "interop.Greeter", Map.of());
        WireServer wireServer = new WireServer(url, registry);
        wireServer.open();
        System.out.println("jaws-wire server started on port " + WIRE_PORT
                + " (with LoggingInterceptor + AuthInterceptor)");

        try {
            ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", WIRE_PORT)
                    .usePlaintext()
                    .build();
            try {
                GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);

                // ---- 1. Call WITHOUT token → UNAUTHENTICATED ----
                System.out.println("\n=== 1. Call Without Token (expect UNAUTHENTICATED) ===");
                try {
                    stub.sayHello(HelloRequest.newBuilder().setName("anonymous").build());
                    throw new AssertionError("expected UNAUTHENTICATED error");
                } catch (StatusRuntimeException e) {
                    System.out.println("Rejected: " + e.getStatus().getCode()
                            + " - " + e.getStatus().getDescription());
                    if (e.getStatus().getCode() != Status.Code.UNAUTHENTICATED) {
                        throw new AssertionError(
                                "expected UNAUTHENTICATED, got: " + e.getStatus().getCode());
                    }
                }

                // ---- 2. Call WITH valid token → success ----
                System.out.println("\n=== 2. Call With Valid Token (expect success) ===");
                Metadata headers = new Metadata();
                headers.put(AUTH_TOKEN_KEY, "secret-token-123");
                HelloReply reply = stub
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .sayHello(HelloRequest.newBuilder().setName("alice").build());
                System.out.println("Response: " + reply.getMessage());
                if (!reply.getMessage().contains("authenticated as alice")) {
                    throw new AssertionError(
                            "expected authenticated user in response, got: " + reply.getMessage());
                }
                System.out.println("Auth interceptor injected x-user=alice, handler saw it.");

                // ---- 3. Server-streaming call WITH token ----
                System.out.println("\n=== 3. Server Streaming With Token ===");
                GreeterGrpc.GreeterStub asyncStub = GreeterGrpc.newStub(channel);
                CountDownLatch latch = new CountDownLatch(1);
                int[] count = {0};
                asyncStub
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .sayHelloStream(
                                HelloRequest.newBuilder().setName("bob").build(),
                                new io.grpc.stub.StreamObserver<>() {
                                    @Override
                                    public void onNext(HelloReply value) {
                                        count[0]++;
                                        System.out.println("  stream item: " + value.getMessage());
                                    }

                                    @Override
                                    public void onError(Throwable t) {
                                        System.err.println("  stream error: " + t.getMessage());
                                        latch.countDown();
                                    }

                                    @Override
                                    public void onCompleted() {
                                        System.out.println("  stream completed (" + count[0] + " items)");
                                        latch.countDown();
                                    }
                                });
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("server-streaming call timed out");
                }
                if (count[0] != 3) {
                    throw new AssertionError("expected 3 stream items, got: " + count[0]);
                }

                System.out.println("\n=== WireServerInterceptor Demo Passed ===");
            } finally {
                channel.shutdown();
            }
        } finally {
            wireServer.close();
        }
    }

    // ---- Interceptor implementations ----

    /**
     * Outermost interceptor that logs every call's path, elapsed time, and
     * close status. Wraps the {@link WireServerCall} (Forwarding pattern) to
     * observe the outbound response without modifying it.
     */
    private static class LoggingInterceptor implements WireServerInterceptor {
        @Override
        public WireServerListener interceptCall(WireServerCall call, Message request,
                                                WireServerCallHandler next) {
            long startTime = System.nanoTime();
            String path = call.path();
            System.out.println("[LoggingInterceptor] >>> " + path);

            // Wrap the call to observe the close status
            WireServerCall wrappedCall = new WireServerCall() {
                @Override
                public WireCallContext context() {
                    return call.context();
                }

                @Override
                public String path() {
                    return call.path();
                }

                @Override
                public void sendMessage(Message response) {
                    call.sendMessage(response);
                }

                @Override
                public void dispatchStream(StreamSource<Message> source) {
                    call.dispatchStream(source);
                }

                @Override
                public void close(int status, String message) {
                    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                    String statusName = WireStatus.nameOf(status);
                    System.out.println("[LoggingInterceptor] <<< " + path
                            + " status=" + statusName
                            + " elapsed=" + elapsedMs + "ms");
                    call.close(status, message);
                }

                @Override
                public boolean isCancelled() {
                    return call.isCancelled();
                }
            };

            return next.startCall(wrappedCall, request);
        }
    }

    /**
     * Innermost interceptor that checks for an {@code x-auth-token} metadata
     * entry. If absent, short-circuits with {@code UNAUTHENTICATED}. If present,
     * maps the token to a user name and injects {@code x-user} into the
     * {@link WireCallContext} for downstream handlers.
     */
    private static class AuthInterceptor implements WireServerInterceptor {

        /** Simulated token-to-user mapping. */
        private static String resolveUser(String token) {
            return switch (token) {
                case "secret-token-123" -> "alice";
                case "admin-token-456" -> "admin";
                default -> null;
            };
        }

        @Override
        public WireServerListener interceptCall(WireServerCall call, Message request,
                                                WireServerCallHandler next) {
            String token = call.context().getAttachment("x-auth-token");
            if (token == null || token.isEmpty()) {
                System.out.println("[AuthInterceptor] No token found, rejecting call");
                call.close(WireConstants.STATUS_UNAUTHENTICATED, "Missing x-auth-token");
                // Return a no-op listener; the call is already closed
                return new WireServerListener() {};
            }

            String user = resolveUser(token);
            if (user == null) {
                System.out.println("[AuthInterceptor] Invalid token: " + token);
                call.close(WireConstants.STATUS_UNAUTHENTICATED, "Invalid token");
                return new WireServerListener() {};
            }

            System.out.println("[AuthInterceptor] Token accepted, user=" + user);
            // Inject x-user into the call context for downstream handlers
            call.context().putAttachment("x-user", user);
            return next.startCall(call, request);
        }
    }
}
