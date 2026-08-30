package org.hongxi.jaws.transport.http2;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Http2ServerStreamHandler} reject path: when the
 * business thread pool is full (AbortPolicyWithStats), the stream must be
 * answered with 503 instead of hanging until timeout, and the active-request
 * counter incremented before submission must not leak.
 *
 * @author shenhongxi
 */
class Http2ServerStreamHandlerTest {

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
        AtomicInteger activeRequests = new AtomicInteger();
        EmbeddedChannel ch = new EmbeddedChannel(new Http2ServerStreamHandler(
                message -> CompletableFuture.completedFuture(null),
                REJECTING_EXECUTOR, "hessian2", activeRequests, MAX_CONTENT_LENGTH));

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
        assertEquals(0, activeRequests.get(), "activeRequests leaked on rejection");
        ch.finishAndReleaseAll();
    }
}
