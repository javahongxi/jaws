package org.hongxi.jaws.sample.wire.interop;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireCallContext;
import org.hongxi.jaws.wire.WireMethodHandler;
import org.hongxi.jaws.wire.WireMethodHandler.MethodType;
import org.hongxi.jaws.wire.WireServer;
import org.hongxi.jaws.wire.WireHandlerRegistry;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Proves that a standard grpc-java client can call a jaws-wire business handler
 * and that gRPC metadata flows end-to-end through {@link WireCallContext}:
 * <ol>
 *   <li>Start a {@link WireServer} with business {@link WireMethodHandler}s
 *       registered via {@link WireHandlerRegistry} — both unary and
 *       server-streaming</li>
 *   <li>The unary handler overrides {@code handle(Message, WireCallContext)} to
 *       read the {@code x-trace-id} inbound metadata and echo it in the
 *       response</li>
 *   <li>The streaming handler overrides {@code methodType()} to return
 *       {@link MethodType#SERVER_STREAMING} and {@code handleStream()} to emit
 *       multiple replies via a cold {@link Flow.Publisher}</li>
 *   <li>A grpc-java client sends unary calls with {@code x-trace-id} attached
 *       via {@link MetadataUtils} and a server-streaming call with an async
 *       stub</li>
 * </ol>
 * <p>
 * This demonstrates the reverse-direction metadata path and server-streaming:
 * grpc-java client → HTTP/2 headers → {@code WireMetadata.fromHeaders()} →
 * {@link WireCallContext} → handler.
 * <p>
 * Run:
 * <pre>
 *   ./mvnw -q compile exec:java -pl jaws-samples/jaws-sample-wire-interop -am \
 *       -Dexec.mainClass="org.hongxi.jaws.sample.wire.interop.GrpcCallWireDemo"
 * </pre>
 */
public class GrpcCallWireDemo {

    private static final int WIRE_PORT = 50064;

    private static final Metadata.Key<String> TRACE_ID_KEY =
            Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);

    public static void main(String[] args) throws Exception {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("interop.Greeter", "SayHello", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                return handle(request, WireCallContext.EMPTY);
            }

            @Override
            public Message handle(Message request, WireCallContext context) {
                HelloRequest req = (HelloRequest) request;
                String traceId = context.getAttachment("x-trace-id");
                String reply = "Hello, " + req.getName() + "! (from jaws-wire)";
                if (traceId != null) {
                    reply += " [x-trace-id=" + traceId + "]";
                }
                System.out.println("[jaws-wire server] Received: " + req.getName()
                        + (traceId != null ? ", x-trace-id=" + traceId : ""));
                return HelloReply.newBuilder().setMessage(reply).build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HelloRequest.parser();
            }
        });

        // ---- server-streaming handler ----
        registry.register("interop.Greeter", "SayHelloStream", new WireMethodHandler() {
            @Override
            public MethodType methodType() {
                return MethodType.SERVER_STREAMING;
            }

            @Override
            public Message handle(Message request) {
                throw new UnsupportedOperationException("streaming method");
            }

            @Override
            public Flow.Publisher<Message> handleStream(Message request, WireCallContext context) {
                HelloRequest req = (HelloRequest) request;
                String traceId = context.getAttachment("x-trace-id");
                String suffix = traceId != null ? " [x-trace-id=" + traceId + "]" : "";
                System.out.println("[jaws-wire server] Streaming request: " + req.getName()
                        + (traceId != null ? ", x-trace-id=" + traceId : ""));
                // cold publisher: emit items only after subscription
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            // emit 3 greeting replies
                            for (int i = 1; i <= 3; i++) {
                                String msg = "Hello #" + i + ", " + req.getName() + "!" + suffix;
                                subscriber.onNext(
                                        HelloReply.newBuilder().setMessage(msg).build());
                            }
                            subscriber.onComplete();
                        }

                        @Override
                        public void cancel() {
                        }
                    });
                };
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
                + " (business handler with WireCallContext)");

        try {
            ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", WIRE_PORT)
                    .usePlaintext()
                    .build();
            try {
                // ---- 1. Unary call without metadata ----
                System.out.println("\n=== 1. Unary Call (no metadata) ===");
                GreeterGrpc.GreeterBlockingStub stub = GreeterGrpc.newBlockingStub(channel);
                HelloReply reply = stub.sayHello(
                        HelloRequest.newBuilder().setName("grpc-java-client").build());
                System.out.println("Response: " + reply.getMessage());

                // ---- 2. Unary call with metadata ----
                System.out.println("\n=== 2. Unary Call + Metadata via WireCallContext ===");
                Metadata headers = new Metadata();
                headers.put(TRACE_ID_KEY, "trace-xyz-789");
                HelloReply replyWithMeta = stub
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .sayHello(HelloRequest.newBuilder().setName("grpc-java-client").build());
                System.out.println("Response: " + replyWithMeta.getMessage());

                if (!replyWithMeta.getMessage().contains("x-trace-id=trace-xyz-789")) {
                    throw new AssertionError(
                            "expected metadata echoed in response, got: " + replyWithMeta.getMessage());
                }
                System.out.println("Metadata flowed end-to-end: "
                        + "grpc-java -> HTTP/2 headers -> WireCallContext -> handler");

                // ---- 3. Server-streaming call ----
                System.out.println("\n=== 3. Server Streaming Call ===");
                GreeterGrpc.GreeterStub asyncStub = GreeterGrpc.newStub(channel);
                CountDownLatch streamLatch = new CountDownLatch(1);
                asyncStub.sayHelloStream(
                        HelloRequest.newBuilder().setName("grpc-stream-client").build(),
                        new StreamObserver<HelloReply>() {
                            @Override
                            public void onNext(HelloReply value) {
                                System.out.println("  stream item: " + value.getMessage());
                            }

                            @Override
                            public void onError(Throwable t) {
                                System.err.println("  stream error: " + t.getMessage());
                                streamLatch.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                System.out.println("  stream completed");
                                streamLatch.countDown();
                            }
                        });
                if (!streamLatch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("server-streaming call timed out");
                }

                // ---- 4. Server-streaming call + metadata ----
                System.out.println("\n=== 4. Server Streaming Call + Metadata ===");
                CountDownLatch metaLatch = new CountDownLatch(1);
                int[] itemCount = {0};
                asyncStub
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                        .sayHelloStream(
                                HelloRequest.newBuilder().setName("grpc-stream-client").build(),
                                new StreamObserver<HelloReply>() {
                                    @Override
                                    public void onNext(HelloReply value) {
                                        itemCount[0]++;
                                        System.out.println("  stream item: " + value.getMessage());
                                    }

                                    @Override
                                    public void onError(Throwable t) {
                                        System.err.println("  stream error: " + t.getMessage());
                                        metaLatch.countDown();
                                    }

                                    @Override
                                    public void onCompleted() {
                                        System.out.println("  stream completed (" + itemCount[0] + " items)");
                                        metaLatch.countDown();
                                    }
                                });
                if (!metaLatch.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("server-streaming + metadata call timed out");
                }
                if (itemCount[0] != 3) {
                    throw new AssertionError(
                            "expected 3 stream items, got: " + itemCount[0]);
                }
            } finally {
                channel.shutdown();
            }

            System.out.println("\n=== grpc-java -> jaws-wire Business Call Passed ===");
        } finally {
            wireServer.close();
        }

        System.exit(0);
    }
}
