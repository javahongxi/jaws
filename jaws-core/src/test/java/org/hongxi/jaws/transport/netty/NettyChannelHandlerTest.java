package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NettyChannelHandler reference counting and thread-pool dispatch.
 * <p>
 * Core regression point: whether a message goes through async execution, thread-pool
 * rejection, or the sync processing path, the reference count of the ByteBuf carried by
 * {@code DecodedFrame.data()} must drop to zero after {@code channelRead} returns —
 * a correctness constraint introduced by converting the Codec interface to ByteBuf
 * (the readRetainedSlice zero-copy refactor).
 */
class NettyChannelHandlerTest {

    private ThreadPoolExecutor executor;
    private EmbeddedChannel embeddedChannel;

    @AfterEach
    void tearDown() {
        if (embeddedChannel != null) {
            embeddedChannel.finishAndReleaseAll();
            embeddedChannel.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ---------- reject path: refCnt must return to zero ----------

    @Test
    void rejectedMessageReleasesByteBuf() throws Exception {
        // Saturate a single-thread pool with a blocking task so subsequent
        // submissions throw RejectedExecutionException (AbortPolicy).
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        saturated.execute(() -> {
            taskStarted.countDown();
            try {
                block.await();
            } catch (InterruptedException ignored) {
            }
        });
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
        this.executor = saturated;

        FakeChannel jawsChannel = fakeChannel();
        CountingHandler messageHandler = new CountingHandler();
        NettyChannelHandler handler = new NettyChannelHandler(jawsChannel, messageHandler, saturated);
        embeddedChannel = new EmbeddedChannel(handler);

        ByteBuf data = Unpooled.buffer();
        encodeSampleRequest(data, 99L);
        DecodedFrame message = new DecodedFrame(true, 99L, data);

        // refCnt == 1 while in flight
        assertEquals(1, data.refCnt());

        embeddedChannel.writeInbound(message);

        // Regression guard for the reject-path leak fix: before the fix,
        // the retain() before execute() was never released when the executor
        // rejected the task, leaving refCnt at 1 forever.
        assertEquals(0, data.refCnt(), "ByteBuf leaked on thread-pool rejection path");

        // The reject path must still answer the client with an error response
        ByteBuf outbound = pollOutbound(embeddedChannel);
        assertNotNull(outbound, "reject path should write an error response");
        assertTrue(outbound.readableBytes() > JawsCodec.HEADER_LENGTH);
        assertEquals(JawsCodec.MAGIC, outbound.readShort());
        outbound.release();

        // The rejected request never reaches the business handler
        assertEquals(0, messageHandler.handled.get());

        block.countDown();
    }

    // ---------- async path: refCnt must return to zero ----------

    @Test
    void asyncProcessedMessageReleasesByteBufAndResponds() throws Exception {
        executor = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new SynchronousQueue<>());

        FakeChannel jawsChannel = fakeChannel();
        CountingHandler messageHandler = new CountingHandler();
        NettyChannelHandler handler = new NettyChannelHandler(jawsChannel, messageHandler, executor);
        embeddedChannel = new EmbeddedChannel(handler);

        ByteBuf data = Unpooled.buffer();
        encodeSampleRequest(data, 100L);
        DecodedFrame message = new DecodedFrame(true, 100L, data);

        embeddedChannel.writeInbound(message);

        // Wait for the async task to finish processing and release its reference
        long deadline = System.currentTimeMillis() + 5000;
        while (data.refCnt() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, data.refCnt(), "ByteBuf leaked on async processing path");

        // Business handler saw the decoded request, and a response went out
        long awaitDeadline = System.currentTimeMillis() + 5000;
        while (messageHandler.handled.get() == 0 && System.currentTimeMillis() < awaitDeadline) {
            Thread.sleep(10);
        }
        assertEquals(1, messageHandler.handled.get());
        assertNotNull(messageHandler.lastRequest);
        assertEquals(100L, messageHandler.lastRequest.getRequestId());

        ByteBuf outbound = pollOutbound(embeddedChannel);
        assertNotNull(outbound, "processed request should produce a response");
        assertEquals(JawsCodec.MAGIC, outbound.readShort());
        outbound.release();
    }

    // ---------- sync path (no executor): refCnt must return to zero ----------

    @Test
    void syncProcessedMessageReleasesByteBuf() throws InterruptedException {
        FakeChannel jawsChannel = fakeChannel();
        CountingHandler messageHandler = new CountingHandler();
        NettyChannelHandler handler = new NettyChannelHandler(jawsChannel, messageHandler);
        embeddedChannel = new EmbeddedChannel(handler);

        ByteBuf data = Unpooled.buffer();
        encodeSampleRequest(data, 101L);
        DecodedFrame message = new DecodedFrame(true, 101L, data);

        embeddedChannel.writeInbound(message);

        assertEquals(0, data.refCnt(), "ByteBuf leaked on sync processing path");
        assertEquals(1, messageHandler.handled.get());

        ByteBuf outbound = pollOutbound(embeddedChannel);
        assertNotNull(outbound);
        outbound.release();
    }

    // ---------- unsupported message type ----------

    @Test
    void unsupportedMessageTypeClosesChannel() throws InterruptedException {
        FakeChannel jawsChannel = fakeChannel();
        CountingHandler messageHandler = new CountingHandler();
        NettyChannelHandler handler = new NettyChannelHandler(jawsChannel, messageHandler);
        embeddedChannel = new EmbeddedChannel(handler);

        // exceptionCaught logs and closes the channel; writeInbound does not propagate
        embeddedChannel.writeInbound("not-a-netty-message");

        // drain event loop so exceptionCaught runs before we assert
        embeddedChannel.runPendingTasks();
        assertFalse(embeddedChannel.isOpen(), "unsupported message type should close the channel");
        assertEquals(0, messageHandler.handled.get());
    }

    // ---------- helpers ----------

    private FakeChannel fakeChannel() {
        Map<String, String> params = new HashMap<>();
        params.put("serialization", "hessian2");
        return new FakeChannel(new URL("jaws", "127.0.0.1", 18002, "test", params));
    }

    private void encodeSampleRequest(ByteBuf buf, long requestId) {
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(requestId);
        request.setInterfaceName("org.hongxi.jaws.FooService");
        request.setMethodName("hello");
        request.setParamDesc("");
        request.setArguments(null);
        try {
            JawsCodec.encode(fakeChannel(), request, buf);
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode sample request", e);
        }
    }

    /** Polls the embedded channel outbound queue, draining pending executor tasks first. */
    private ByteBuf pollOutbound(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        ByteBuf outbound = channel.readOutbound();
        while (outbound == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
            outbound = channel.readOutbound();
        }
        return outbound;
    }

    /** Fake jaws Channel satisfying the URL lookup contract. */
    private static class FakeChannel implements Channel {
        private final URL url;

        FakeChannel(URL url) {
            this.url = url;
        }

        @Override
        public boolean open() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(int timeout) {
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public URL getUrl() {
            return url;
        }
    }

    /** Counts handled requests; returns a fixed successful response. */
    private static class CountingHandler implements MessageHandler {
        final java.util.concurrent.atomic.AtomicInteger handled = new java.util.concurrent.atomic.AtomicInteger();
        volatile Request lastRequest;

        @Override
        public CompletableFuture<Object> handleAsync(Object message) {
            if (message instanceof Request request) {
                lastRequest = request;
                handled.incrementAndGet();
                return CompletableFuture.completedFuture("ok-" + request.getRequestId());
            }
            return CompletableFuture.completedFuture(message);
        }
    }
}
