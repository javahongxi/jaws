package org.hongxi.jaws.transport.http2;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Http2StreamServerHandler}, pinned around two invariants:
 * <ul>
 *   <li><b>Back-pressure at the boundary</b> — when the business thread pool is
 *       full (AbortPolicyWithStats) the stream is answered with 503 instead of
 *       hanging until timeout, and the counter taken before submission is
 *       always given back.</li>
 *   <li><b>Frame ordering</b> — a streaming response must never put a DATA frame
 *       ahead of its own HEADERS, whichever thread the response items are
 *       produced on, and the in-flight counter is only released once the
 *       terminal frame has actually been committed to the pipeline.</li>
 * </ul>
 *
 * @author shenhongxi
 */
class Http2StreamServerHandlerTest {

    private static final int MAX_CONTENT_LENGTH = 4 * 1024 * 1024;

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

    @Test
    void rejectedRequestGetsServiceUnavailable() {
        AtomicInteger inflightRequests = new AtomicInteger();
        EmbeddedChannel ch = new EmbeddedChannel(new Http2StreamServerHandler(
                message -> CompletableFuture.completedFuture(null),
                REJECTING_EXECUTOR, "hessian2", inflightRequests, MAX_CONTENT_LENGTH));

        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST").path(Http2Constants.PATH);
        ch.writeInbound(new DefaultHttp2HeadersFrame(headers, false));
        ch.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[16]), true));

        // The rejected stream is answered with 503, not left hanging
        Http2HeadersFrame errorHeaders = ch.readOutbound();
        assertEquals(Http2Constants.STATUS_SERVICE_UNAVAILABLE,
                errorHeaders.headers().status().toString());
        Http2DataFrame errorData = ch.readOutbound();
        assertTrue(errorData.isEndStream());

        // The counter incremented before execute() must be balanced
        assertEquals(0, inflightRequests.get(), "inflightRequests leaked on rejection");
        ch.finishAndReleaseAll();
    }

    /**
     * Runs submitted tasks inline, which reproduces the shape that broke: a
     * business handler that delivers response items from whichever thread
     * produced them, including the event loop itself.
     */
    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        @Override
        public void execute(Runnable command) {
            command.run();
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

    private static Http2Headers streamHeaders(StreamType streamType) {
        return new DefaultHttp2Headers()
                .method("POST").path(Http2Constants.PATH)
                .set(Http2Constants.HEADER_STREAMING, streamType.getValue());
    }

    private static DefaultRequest request() {
        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName("org.hongxi.jaws.sample.StreamService");
        request.setMethodName("greetStream");
        request.setParamDesc("");
        request.setArguments(new Object[]{});
        return request;
    }

    /**
     * Asserts the ordering invariant that a streaming response must always hold:
     * the response HEADERS is the first frame out, and every DATA frame — the
     * items and the END_STREAM that closes the stream — follows it.
     */
    private static void assertHeadersFirst(EmbeddedChannel ch, StreamType streamType) {
        ch.runPendingTasks();
        Http2HeadersFrame headers = ch.readOutbound();
        assertNotNull(headers, "a streaming response must always carry HEADERS");
        assertEquals(Http2Constants.STATUS_OK, headers.headers().status().toString());
        assertEquals(streamType.getValue(), headers.headers().get(Http2Constants.HEADER_STREAMING).toString(),
                "HEADERS must announce the streaming mode before any DATA");
    }

    @Test
    void serverStreamingWritesHeadersBeforeBufferedItems() throws Exception {
        // A hot source: items are produced before the framework subscribes,
        // which is the normal shape of a server-streaming method that returns a
        // StreamSubject it has already been writing into.
        StreamSubject<Object> hot = new StreamSubject<>();
        hot.onNext("item-1");
        hot.onNext("item-2");
        hot.onCompleted();

        AtomicInteger inflightRequests = new AtomicInteger();
        EmbeddedChannel ch = new EmbeddedChannel(new Http2StreamServerHandler(
                new StreamingHandler(hot, null),
                DIRECT_EXECUTOR, "hessian2", inflightRequests, MAX_CONTENT_LENGTH));

        ch.writeInbound(new DefaultHttp2HeadersFrame(streamHeaders(StreamType.SERVER), false));
        ch.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(
                Http2PayloadCodec.encodeRequest(request(), Http2PayloadCodec.resolveSerialization("hessian2"))), true));

        assertHeadersFirst(ch, StreamType.SERVER);
        Http2DataFrame first = ch.readOutbound();
        assertFalse(first.isEndStream(), "an item frame must not close the stream");
        Http2DataFrame second = ch.readOutbound();
        assertFalse(second.isEndStream());
        Http2DataFrame last = ch.readOutbound();
        assertTrue(last.isEndStream(), "the terminal frame must carry END_STREAM");
        assertEquals(0, inflightRequests.get(), "inflightRequests must be released once the stream ends");
        ch.finishAndReleaseAll();
    }

    @Test
    void emptyStreamStillSendsHeaders() throws Exception {
        StreamSubject<Object> empty = new StreamSubject<>();
        empty.onCompleted();

        AtomicInteger inflightRequests = new AtomicInteger();
        EmbeddedChannel ch = new EmbeddedChannel(new Http2StreamServerHandler(
                new StreamingHandler(empty, null),
                DIRECT_EXECUTOR, "hessian2", inflightRequests, MAX_CONTENT_LENGTH));

        ch.writeInbound(new DefaultHttp2HeadersFrame(streamHeaders(StreamType.SERVER), false));
        ch.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(
                Http2PayloadCodec.encodeRequest(request(), Http2PayloadCodec.resolveSerialization("hessian2"))), true));

        assertHeadersFirst(ch, StreamType.SERVER);
        Http2DataFrame end = ch.readOutbound();
        assertTrue(end.isEndStream());
        assertEquals(0, inflightRequests.get());
        ch.finishAndReleaseAll();
    }

    @Test
    void failureBeforeFirstItemStillSendsHeaders() throws Exception {
        StreamSubject<Object> failed = new StreamSubject<>();
        failed.onError(new RuntimeException("boom"));

        AtomicInteger inflightRequests = new AtomicInteger();
        EmbeddedChannel ch = new EmbeddedChannel(new Http2StreamServerHandler(
                new StreamingHandler(failed, null),
                DIRECT_EXECUTOR, "hessian2", inflightRequests, MAX_CONTENT_LENGTH));

        ch.writeInbound(new DefaultHttp2HeadersFrame(streamHeaders(StreamType.SERVER), false));
        ch.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(
                Http2PayloadCodec.encodeRequest(request(), Http2PayloadCodec.resolveSerialization("hessian2"))), true));

        assertHeadersFirst(ch, StreamType.SERVER);
        Http2DataFrame error = ch.readOutbound();
        assertTrue(error.isEndStream(), "the error frame closes the stream");
        assertEquals(0, inflightRequests.get());
        ch.finishAndReleaseAll();
    }

    @Test
    void bidiEchoKeepsHeadersAheadOfInlineWrite() throws Exception {
        Serialization serialization = Http2PayloadCodec.resolveSerialization("hessian2");

        // The response is produced from inside the request observer's onNext,
        // i.e. on the thread that decoded the inbound DATA frame — the exact
        // shape that let an inline DATA jump a queued HEADERS.
        StreamSubject<Object> response = new StreamSubject<>();
        StreamingHandler handler = new StreamingHandler(response, item -> response.onNext("echo:" + item));

        AtomicInteger inflightRequests = new AtomicInteger();
        EmbeddedChannel ch = new EmbeddedChannel(new Http2StreamServerHandler(
                handler, DIRECT_EXECUTOR, "hessian2", inflightRequests, MAX_CONTENT_LENGTH));

        DefaultRequest request = request();
        request.setMethodName("bidiGreet");
        ch.writeInbound(new DefaultHttp2HeadersFrame(streamHeaders(StreamType.BIDIRECTIONAL), false));
        ch.writeInbound(new DefaultHttp2DataFrame(
                Unpooled.wrappedBuffer(Http2PayloadCodec.encodeRequest(request, serialization)), false));
        ch.writeInbound(new DefaultHttp2DataFrame(
                Unpooled.wrappedBuffer(Http2StreamCodec.encodeItem("bob", serialization)), false));
        ch.writeInbound(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[0]), true));

        assertHeadersFirst(ch, StreamType.BIDIRECTIONAL);
        Http2DataFrame echoed = ch.readOutbound();
        assertFalse(echoed.isEndStream());
        Http2DataFrame end = ch.readOutbound();
        assertTrue(end.isEndStream());
        assertEquals(0, inflightRequests.get(), "inflightRequests must be released by the terminal frame");
        ch.finishAndReleaseAll();
    }

    /**
     * Streams a canned {@link StreamSubject} back, optionally echoing every
     * request item into it first.
     */
    private static final class StreamingHandler implements MessageHandler {
        private final StreamSource<Object> source;
        private final Consumer<Object> onItem;

        StreamingHandler(StreamSource<Object> source, Consumer<Object> onItem) {
            this.source = source;
            this.onItem = onItem;
        }

        @Override
        public CompletableFuture<Object> handleAsync(Object message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public StreamSource<Object> handleStream(Request request, StreamObserver<Object> requestStream) {
            if (onItem != null) {
                // The framework hands the business a StreamSubject as an observer;
                // consuming request items means subscribing to it, which is what
                // the sample services do too.
                ((StreamSource<Object>) requestStream).subscribe(new StreamObserver<Object>() {
                    @Override
                    public void onNext(Object item) {
                        onItem.accept(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        failResponse(throwable);
                    }

                    @Override
                    public void onCompleted() {
                        completeResponse();
                    }
                });
            }
            return source;
        }

        /** Mirror the request stream's terminal signal onto the response stream. */
        private void completeResponse() {
            if (source instanceof StreamObserver<?> observer) {
                @SuppressWarnings("unchecked")
                StreamObserver<Object> typed = (StreamObserver<Object>) observer;
                typed.onCompleted();
            }
        }

        private void failResponse(Throwable throwable) {
            if (source instanceof StreamObserver<?> observer) {
                @SuppressWarnings("unchecked")
                StreamObserver<Object> typed = (StreamObserver<Object>) observer;
                typed.onError(throwable);
            }
        }
    }
}
