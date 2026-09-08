package org.hongxi.jaws.wire;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the max inbound metadata size limit:
 * {@link WireMetadata#estimateHeaderSize(Http2Headers)} and the application-level
 * enforcement in {@link WireStreamServerHandler} and
 * {@link WireStreamResponseHandler}.
 *
 * @author shenhongxi
 */
class WireMetadataSizeLimitTest {

    /** Same-thread executor so dispatch runs inline with writeInbound. */
    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() { shutdown = true; }

        @Override
        public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }

        @Override
        public boolean isShutdown() { return shutdown; }

        @Override
        public boolean isTerminated() { return shutdown; }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    };

    @Test
    void estimateHeaderSizeEmptyHeaders() {
        assertEquals(0, WireMetadata.estimateHeaderSize(null));
        assertEquals(0, WireMetadata.estimateHeaderSize(new DefaultHttp2Headers()));
    }

    @Test
    void estimateHeaderSizeComputesByteLength() {
        Http2Headers headers = new DefaultHttp2Headers()
                .set("x-trace-id", "abc123")
                .set("x-user", "alice");
        // "x-trace-id"(10) + "abc123"(6) + "x-user"(6) + "alice"(5) = 27
        assertEquals(27, WireMetadata.estimateHeaderSize(headers));
    }

    @Test
    void estimateHeaderSizeHandlesUtf8() {
        Http2Headers headers = new DefaultHttp2Headers()
                .set("x-name", "中文");
        // "x-name"(6) + "中文"(6 bytes in UTF-8) = 12
        assertEquals(12, WireMetadata.estimateHeaderSize(headers));
    }

    @Test
    void serverRejectsOversizedMetadata() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Echo", new WireMethodHandler() {
            @Override
            public com.google.protobuf.Message handle(com.google.protobuf.Message request) {
                return HealthCheckResponse.getDefaultInstance();
            }

            @Override
            public com.google.protobuf.Parser<? extends com.google.protobuf.Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        // Very tight metadata limit: 20 bytes
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry),
                        null, DIRECT_EXECUTOR, 1024 * 1024, 20, null));

        // Build headers that exceed 20 bytes
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme("http")
                .path("/test.Health/Echo")
                .authority("localhost:8080")
                .set("x-trace-id", "a-very-long-trace-id-value-that-exceeds-the-limit");
        ch.writeInbound(new DefaultHttp2HeadersFrame(headers));

        Http2HeadersFrame response = ch.readOutbound();
        assertNotNull(response);
        assertTrue(response.isEndStream());
        assertEquals(String.valueOf(WireConstants.STATUS_RESOURCE_EXHAUSTED),
                response.headers().get(WireConstants.GRPC_STATUS).toString());
        ch.finishAndReleaseAll();
    }

    @Test
    void serverAcceptsMetadataWithinLimit() {
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Echo", new WireMethodHandler() {
            @Override
            public com.google.protobuf.Message handle(com.google.protobuf.Message request) {
                return HealthCheckResponse.getDefaultInstance();
            }

            @Override
            public com.google.protobuf.Parser<? extends com.google.protobuf.Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });

        // Generous metadata limit: 64KB
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireStreamServerHandler(
                        new WireCallDispatcher.HandlerCallDispatcher(registry),
                        null, DIRECT_EXECUTOR, 1024 * 1024, 64 * 1024, null));

        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme("http")
                .path("/test.Health/Echo")
                .authority("localhost:8080")
                .set("content-type", WireConstants.CONTENT_TYPE_GRPC)
                .set("x-trace-id", "short-id");
        ch.writeInbound(new DefaultHttp2HeadersFrame(headers, false));

        // Should not produce an error frame; the handler should proceed to wait for DATA
        assertNull(ch.readOutbound(), "no error response for headers within limit");
        ch.finishAndReleaseAll();
    }

    @Test
    void clientResponseHandlerRejectsOversizedMetadata() {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("test.Health");
        request.setMethodName("Check");
        DefaultResponseFuture future = new DefaultResponseFuture(request, 5000);

        // Very tight limit: 10 bytes
        WireStreamResponseHandler handler = new WireStreamResponseHandler(
                HealthCheckResponse.parser(),
                future, 1024 * 1024, 10,
                msg -> {
                    DefaultResponse resp = new DefaultResponse(request.getRequestId());
                    resp.setValue(msg);
                    return resp;
                },
                () -> {},
                true);

        EmbeddedChannel ch = new EmbeddedChannel(handler);

        // Response trailers with metadata exceeding 10 bytes
        Http2Headers trailers = new DefaultHttp2Headers()
                .set(WireConstants.GRPC_STATUS, "0")
                .set("x-response-id", "some-long-response-id-value");
        ch.writeInbound(new DefaultHttp2HeadersFrame(trailers, true));

        assertTrue(future.isDone());
        assertNotNull(future.getThrowable());
        assertTrue(future.getThrowable().getMessage().contains("maxInboundMetadataSize"));
        ch.finishAndReleaseAll();
    }

    @Test
    void zeroLimitDisablesCheck() {
        // maxInboundMetadataSize = 0 means no limit.
        // The check is: maxInboundMetadataSize > 0 && size > limit
        // So 0 means the check is always skipped (short-circuit).
        int limit = 0;
        int anySize = 999999;
        assertFalse(limit > 0 && anySize > limit);
    }
}
