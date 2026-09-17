package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the redesigned {@link WireServerInterceptor} supporting all four
 * gRPC call types with the grpc-java-style interceptCall / WireServerCall /
 * WireServerListener pattern.
 */
class WireServerInterceptorTest {

    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        private volatile boolean shutdown;

        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    };

    private static final HealthCheckRequest REQUEST =
            HealthCheckRequest.newBuilder().setService("test").build();

    private static Http2HeadersFrame requestHeaders(String path) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST").scheme("http").path(path).authority("localhost")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS);
        return new DefaultHttp2HeadersFrame(headers, false);
    }

    private static Http2HeadersFrame requestHeaders(String path, Http2Headers extra) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST").scheme("http").path(path).authority("localhost")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS);
        for (var entry : extra) {
            headers.set(entry.getKey(), entry.getValue());
        }
        return new DefaultHttp2HeadersFrame(headers, false);
    }

    // ========================================================================
    // Unary: interceptor can observe request and response
    // ========================================================================

    @Test
    void unaryInterceptorObservesRequestAndResponse() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        AtomicReference<String> interceptedPath = new AtomicReference<>();
        AtomicReference<String> interceptedToken = new AtomicReference<>();

        registry.addInterceptor((call, request, next) -> {
            interceptedPath.set(call.path());
            interceptedToken.set(call.context().getAttachment("authorization"));
            return next.startCall(call, request);
        });

        registry.register("test.Health", "Check", new WireMethodHandler() {
            @Override
            public Message handle(Message request, WireCallContext context) {
                return HealthCheckResponse.newBuilder()
                        .setStatus(HealthCheckResponse.ServingStatus.SERVING).build();
            }

            @Override
            public com.google.protobuf.Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                        null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null));

        Http2Headers extra = new DefaultHttp2Headers().set("authorization", "Bearer test-token");
        ch.writeInbound(requestHeaders("/test.Health/Check", extra));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        assertEquals("/test.Health/Check", interceptedPath.get());
        assertEquals("Bearer test-token", interceptedToken.get());

        // Verify response was sent (headers + data + trailers)
        assertNotNull(ch.readOutbound());
        ch.finishAndReleaseAll();
    }

    // ========================================================================
    // Unary: interceptor can short-circuit with close()
    // ========================================================================

    @Test
    void unaryInterceptorCanRejectCall() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        registry.addInterceptor((call, request, next) -> {
            String token = call.context().getAttachment("authorization");
            if (token == null) {
                call.close(16, "UNAUTHENTICATED");
                return new WireServerListener() {};
            }
            return next.startCall(call, request);
        });

        registry.register("test.Health", "Check", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                handlerCalled.set(true);
                return HealthCheckResponse.newBuilder().build();
            }

            @Override
            public com.google.protobuf.Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                        null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null));

        ch.writeInbound(requestHeaders("/test.Health/Check"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        assertFalse(handlerCalled.get());
        assertNotNull(ch.readOutbound(), "error trailers should be sent");
        ch.finishAndReleaseAll();
    }

    // ========================================================================
    // Unary: Forwarding pattern to observe response
    // ========================================================================

    @Test
    void unaryInterceptorCanObserveResponseViaForwarding() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        AtomicBoolean responseObserved = new AtomicBoolean(false);

        registry.addInterceptor((call, request, next) -> {
            WireServerCall wrappedCall = new ForwardingServerCall(call) {
                @Override
                public void sendMessage(Message response) {
                    responseObserved.set(true);
                    super.sendMessage(response);
                }
            };
            return next.startCall(wrappedCall, request);
        });

        registry.register("test.Health", "Check", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                return HealthCheckResponse.newBuilder()
                        .setStatus(HealthCheckResponse.ServingStatus.SERVING).build();
            }

            @Override
            public com.google.protobuf.Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                        null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null));

        ch.writeInbound(requestHeaders("/test.Health/Check"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        assertTrue(responseObserved.get(), "interceptor should observe the response via Forwarding");
        ch.finishAndReleaseAll();
    }

    // ========================================================================
    // Multiple interceptors form a chain
    // ========================================================================

    @Test
    void multipleInterceptorsFormChain() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        List<String> order = new ArrayList<>();

        registry.addInterceptor((call, request, next) -> {
            order.add("first-before");
            WireServerListener listener = next.startCall(call, request);
            order.add("first-after");
            return listener;
        });

        registry.addInterceptor((call, request, next) -> {
            order.add("second-before");
            WireServerListener listener = next.startCall(call, request);
            order.add("second-after");
            return listener;
        });

        registry.register("test.Health", "Check", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                order.add("handler");
                return HealthCheckResponse.newBuilder().build();
            }

            @Override
            public com.google.protobuf.Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                        null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null));

        ch.writeInbound(requestHeaders("/test.Health/Check"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        // Handler runs during onHalfClose(), after all startCall() returns
        assertEquals(List.of("first-before", "second-before",
                "second-after", "first-after", "handler"), order);

        ch.finishAndReleaseAll();
    }

    // ========================================================================
    // No interceptors: dispatch works as before
    // ========================================================================

    @Test
    void noInterceptorsDispatchsNormally() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        registry.register("test.Health", "Check", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                handlerCalled.set(true);
                return HealthCheckResponse.newBuilder().build();
            }

            @Override
            public com.google.protobuf.Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                        null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null));

        ch.writeInbound(requestHeaders("/test.Health/Check"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        assertTrue(handlerCalled.get());
        ch.finishAndReleaseAll();
    }

    // ========================================================================
    // Interceptor can modify metadata via putAttachment
    // ========================================================================

    @Test
    void interceptorCanModifyMetadata() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        AtomicReference<String> handlerSeenToken = new AtomicReference<>();

        registry.addInterceptor((call, request, next) -> {
            call.context().putAttachment("x-injected", "by-interceptor");
            return next.startCall(call, request);
        });

        registry.register("test.Health", "Check", new WireMethodHandler() {
            @Override
            public Message handle(Message request, WireCallContext context) {
                handlerSeenToken.set(context.getAttachment("x-injected"));
                return HealthCheckResponse.newBuilder().build();
            }

            @Override
            public com.google.protobuf.Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                        null, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, 0, null));

        ch.writeInbound(requestHeaders("/test.Health/Check"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        assertEquals("by-interceptor", handlerSeenToken.get());
        ch.finishAndReleaseAll();
    }
}
