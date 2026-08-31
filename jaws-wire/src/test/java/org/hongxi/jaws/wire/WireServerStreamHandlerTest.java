package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frame-level tests for {@link WireServerStreamHandler} (direct API mode) on
 * an {@link EmbeddedChannel}: unary responses, trailers-only errors, the
 * max-inbound-message-size guard, gzip request decompression, metadata
 * exposure, deadline enforcement, and caller cancellation.
 *
 * @author shenhongxi
 */
class WireServerStreamHandlerTest {

    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    /** Same-thread executor so dispatch runs inline with writeInbound. */
    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    /** Always rejects, simulating a saturated business thread pool. */
    private static final ExecutorService REJECTING_EXECUTOR = new AbstractExecutorService() {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("pool full");
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    private static final HealthCheckRequest REQUEST =
            HealthCheckRequest.newBuilder().setService("demo").build();

    private static Http2HeadersFrame requestHeaders(String path) {
        return requestHeaders(path, new DefaultHttp2Headers());
    }

    private static Http2HeadersFrame requestHeaders(String path, Http2Headers extra) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme("http")
                .path(path)
                .authority("localhost")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS);
        for (var entry : extra) {
            headers.set(entry.getKey(), entry.getValue());
        }
        return new DefaultHttp2HeadersFrame(headers, false);
    }

    private static HealthCheckResponse decodeResponseData(Http2DataFrame dataFrame) throws Exception {
        ByteBuf copy = dataFrame.content().copy();
        try {
            return WireFrameCodec.decode(copy, HealthCheckResponse.parser());
        } finally {
            copy.release();
        }
    }

    private static WireMethodHandler echoHandler() {
        return new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                String service = ((HealthCheckRequest) request).getService();
                return HealthCheckResponse.newBuilder()
                        .setStatus(service.isEmpty() ? ServingStatus.UNKNOWN : ServingStatus.SERVING)
                        .build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        };
    }

    @Test
    void unaryCallReturnsHeadersDataAndTrailers() throws Exception {
        WireServiceRegistry registry = new WireServiceRegistry();
        registry.register("test.Health", "Echo", echoHandler());
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, null));

        ch.writeInbound(requestHeaders("/test.Health/Echo"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        Http2HeadersFrame responseHeaders = ch.readOutbound();
        assertEquals("200", responseHeaders.headers().status().toString());
        assertEquals(WireConstants.CONTENT_TYPE_GRPC,
                responseHeaders.headers().get(WireConstants.HEADER_CONTENT_TYPE).toString());
        // The response advertises accepted encodings for follow-up calls
        assertEquals(WireConstants.ACCEPT_ENCODINGS,
                responseHeaders.headers().get(WireConstants.GRPC_ACCEPT_ENCODING).toString());

        Http2DataFrame dataFrame = ch.readOutbound();
        assertEquals(ServingStatus.SERVING, decodeResponseData(dataFrame).getStatus());

        Http2HeadersFrame trailers = ch.readOutbound();
        assertTrue(trailers.isEndStream());
        assertEquals("0", trailers.headers().get(WireConstants.GRPC_STATUS).toString());
        ch.finishAndReleaseAll();
    }

    @Test
    void unknownPathGetsTrailersOnlyNotFound() {
        WireServiceRegistry registry = new WireServiceRegistry();
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, null));

        ch.writeInbound(requestHeaders("/no.Such/Method"));

        // Trailers-only: one HEADERS frame with END_STREAM carrying grpc-status
        Http2HeadersFrame trailersOnly = ch.readOutbound();
        assertNotNull(trailersOnly);
        assertTrue(trailersOnly.isEndStream());
        assertEquals("200", trailersOnly.headers().status().toString());
        assertEquals(String.valueOf(WireConstants.STATUS_NOT_FOUND),
                trailersOnly.headers().get(WireConstants.GRPC_STATUS).toString());
        assertNull(ch.readOutbound(), "no further frames after trailers-only");
        ch.finishAndReleaseAll();
    }

    @Test
    void oversizedMessageGetsResourceExhausted() {
        WireServiceRegistry registry = new WireServiceRegistry();
        registry.register("test.Health", "Echo", echoHandler());
        // Tiny limit: 10 bytes
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, 10, null));

        ch.writeInbound(requestHeaders("/test.Health/Echo"));
        ch.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[100]), true));

        Http2HeadersFrame trailersOnly = ch.readOutbound();
        assertEquals(String.valueOf(WireStatus.STATUS_RESOURCE_EXHAUSTED),
                trailersOnly.headers().get(WireConstants.GRPC_STATUS).toString());
        assertTrue(trailersOnly.isEndStream());
        ch.finishAndReleaseAll();
    }

    @Test
    void gzipCompressedRequestIsDecompressed() throws Exception {
        WireServiceRegistry registry = new WireServiceRegistry();
        AtomicReference<String> seenService = new AtomicReference<>();
        registry.register("test.Health", "Echo", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                seenService.set(((HealthCheckRequest) request).getService());
                return HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, null));

        Http2Headers extra = new DefaultHttp2Headers()
                .set(WireConstants.GRPC_ENCODING, WireConstants.ENCODING_GZIP);
        ch.writeInbound(requestHeaders("/test.Health/Echo", extra));
        ByteBuf compressedFrame = WireFrameCodec.encode(REQUEST, ch.alloc(), WireConstants.ENCODING_GZIP);
        ch.writeInbound(new DefaultHttp2DataFrame(compressedFrame, true));

        assertEquals("demo", seenService.get(), "gzip request must be decompressed before parsing");
        Http2HeadersFrame responseHeaders = ch.readOutbound();
        // Server has no compression configured: no grpc-encoding in the response
        assertNull(responseHeaders.headers().get(WireConstants.GRPC_ENCODING));
        ch.readOutbound(); // DATA
        Http2HeadersFrame trailers = ch.readOutbound();
        assertEquals("0", trailers.headers().get(WireConstants.GRPC_STATUS).toString());
        ch.finishAndReleaseAll();
    }

    @Test
    void unsupportedEncodingGetsUnimplemented() {
        WireServiceRegistry registry = new WireServiceRegistry();
        registry.register("test.Health", "Echo", echoHandler());
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, null));

        Http2Headers extra = new DefaultHttp2Headers().set(WireConstants.GRPC_ENCODING, "zstd");
        ch.writeInbound(requestHeaders("/test.Health/Echo", extra));

        Http2HeadersFrame trailersOnly = ch.readOutbound();
        assertEquals(String.valueOf(WireConstants.STATUS_UNIMPLEMENTED),
                trailersOnly.headers().get(WireConstants.GRPC_STATUS).toString());
        assertNull(ch.readOutbound());
        ch.finishAndReleaseAll();
    }

    @Test
    void customMetadataIsExposedToHandler() {
        WireServiceRegistry registry = new WireServiceRegistry();
        AtomicReference<WireCallContext> seenContext = new AtomicReference<>();
        registry.register("test.Health", "Echo", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                return HealthCheckResponse.getDefaultInstance();
            }

            @Override
            public Message handle(Message request, WireCallContext context) {
                seenContext.set(context);
                return HealthCheckResponse.getDefaultInstance();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, null));

        Http2Headers extra = new DefaultHttp2Headers()
                .set("x-trace-id", "abc123")
                .set("grpc-timeout", "5S"); // reserved, must not leak into metadata
        ch.writeInbound(requestHeaders("/test.Health/Echo", extra));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        assertNotNull(seenContext.get());
        assertEquals("abc123", seenContext.get().getAttachment("x-trace-id"));
        assertNull(seenContext.get().getAttachment("grpc-timeout"));
        ch.finishAndReleaseAll();
    }

    @Test
    void expiredDeadlineGetsDeadlineExceeded() {
        WireServiceRegistry registry = new WireServiceRegistry();
        registry.register("test.Health", "Echo", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                try {
                    // Outrun the 5ms deadline carried by the caller
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return HealthCheckResponse.getDefaultInstance();
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, null));

        Http2Headers extra = new DefaultHttp2Headers().set(WireStatus.GRPC_TIMEOUT, "5m");
        ch.writeInbound(requestHeaders("/test.Health/Echo", extra));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        // The handler ran past the deadline; the result is discarded and the
        // call reports DEADLINE_EXCEEDED without any response DATA
        Http2HeadersFrame trailersOnly = ch.readOutbound();
        assertEquals(String.valueOf(WireStatus.STATUS_DEADLINE_EXCEEDED),
                trailersOnly.headers().get(WireConstants.GRPC_STATUS).toString());
        assertNull(ch.readOutbound(), "no DATA frame once the deadline expired");
        ch.finishAndReleaseAll();
    }

    @Test
    void callerCancelStopsStreamingEmission() {
        WireServiceRegistry registry = new WireServiceRegistry();
        AtomicReference<Flow.Subscriber<? super Message>> capturedSubscriber = new AtomicReference<>();
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        registry.register("test.Health", "Watch", new WireMethodHandler() {
            @Override
            public Message handle(Message request) {
                throw new UnsupportedOperationException("streaming");
            }

            @Override
            public Flow.Publisher<Message> handleStream(Message request) {
                return subscriber -> {
                    capturedSubscriber.set(subscriber);
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                        }

                        @Override
                        public void cancel() {
                            upstreamCancelled.set(true);
                        }
                    });
                };
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        });
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, DIRECT_EXECUTOR, MAX_MESSAGE_SIZE, null));

        ch.writeInbound(requestHeaders("/test.Health/Watch"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));
        assertNotNull(capturedSubscriber.get(), "dispatchStream must have subscribed");

        // Caller cancels with RST_STREAM(CANCEL)
        ch.writeInbound(new DefaultHttp2ResetFrame(Http2Error.CANCEL));

        // Any emission after cancel must be dropped, and the upstream cancelled
        capturedSubscriber.get().onNext(HealthCheckResponse.getDefaultInstance());
        assertNull(ch.readOutbound(), "no frames after caller cancellation");
        assertTrue(upstreamCancelled.get(), "streaming publisher must be cancelled");
        ch.finishAndReleaseAll();
    }

    @Test
    void rejectedCallGetsUnavailable() {
        WireServiceRegistry registry = new WireServiceRegistry();
        registry.register("test.Health", "Echo", echoHandler());
        EmbeddedChannel ch = new EmbeddedChannel(
                new WireServerStreamHandler(registry, REJECTING_EXECUTOR, MAX_MESSAGE_SIZE, null));

        ch.writeInbound(requestHeaders("/test.Health/Echo"));
        ch.writeInbound(new DefaultHttp2DataFrame(
                WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        // The saturated pool must not leave the call hanging: trailers-only
        // UNAVAILABLE so standard gRPC clients treat it as retryable
        Http2HeadersFrame trailersOnly = ch.readOutbound();
        assertTrue(trailersOnly.isEndStream());
        assertEquals(String.valueOf(WireStatus.STATUS_UNAVAILABLE),
                trailersOnly.headers().get(WireConstants.GRPC_STATUS).toString());
        assertNull(ch.readOutbound(), "no further frames after rejection");
        ch.finishAndReleaseAll();
    }
}
