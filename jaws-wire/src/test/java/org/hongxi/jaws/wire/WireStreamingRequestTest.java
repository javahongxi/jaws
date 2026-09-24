package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.hongxi.jaws.wire.health.HealthCheckResponse.ServingStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming request items published before the business handler subscribes,
 * pinned deterministically instead of by racing a real thread pool.
 * <p>
 * The dispatch onto {@code serverExecutor} and the frames arriving on the event
 * loop have no ordering between them, so an item can easily be pushed into the
 * per-stream request {@link StreamSubject} long before {@code handleBidiStream}
 * runs and subscribes. The executor here holds that dispatch until the test
 * releases it, which turns a narrow timing window into a wide, deterministic
 * one: if any item or frame signal were lost in that window, these tests would
 * fail every time rather than one run in ten.
 *
 * @author shenhongxi
 */
class WireStreamingRequestTest {

    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    private static final HealthCheckRequest REQUEST =
            HealthCheckRequest.newBuilder().setService("demo").build();

    private static final HealthCheckResponse RESPONSE =
            HealthCheckResponse.newBuilder().setStatus(ServingStatus.SERVING).build();

    /**
     * Queues submitted tasks and runs none of them until {@link #release()} is
     * called, so the business handler's subscription can be delayed arbitrarily.
     */
    private static final class ParkingExecutor extends AbstractExecutorService {
        private final List<Runnable> parked = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            parked.add(command);
        }

        /** Runs everything held back, on the calling thread. */
        void release() {
            List<Runnable> batch;
            synchronized (this) {
                batch = new ArrayList<>(parked);
                parked.clear();
            }
            for (Runnable task : batch) {
                task.run();
            }
        }

        @Override
        public synchronized void shutdown() {
        }

        @Override
        public synchronized List<Runnable> shutdownNow() {
            List<Runnable> remaining = new ArrayList<>(parked);
            parked.clear();
            return remaining;
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

    /** A bidi handler that records what the request stream actually delivered. */
    private static WireMethodHandler countingBidi(List<Message> received) {
        return new WireMethodHandler() {
            @Override
            public MethodType methodType() {
                return MethodType.BIDIRECTIONAL;
            }

            @Override
            public StreamSource<Message> handleBidiStream(StreamSource<Message> requestStream) {
                StreamSubject<Message> out = new StreamSubject<>();
                requestStream.subscribe(new StreamObserver<>() {
                    @Override
                    public void onNext(Message item) {
                        received.add(item);
                        out.onNext(RESPONSE);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        out.onError(throwable);
                    }

                    @Override
                    public void onCompleted() {
                        out.onCompleted();
                    }
                });
                return out;
            }

            @Override
            public Parser<? extends Message> getRequestParser() {
                return HealthCheckRequest.parser();
            }
        };
    }

    @Test
    void itemsPublishedBeforeSubscriptionAreAllReplayedAndEchoed() {
        List<Message> received = new CopyOnWriteArrayList<>();
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Bidi", countingBidi(received));
        ParkingExecutor executor = new ParkingExecutor();
        EmbeddedChannel ch = new EmbeddedChannel(new WireStreamServerHandler(
                new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                null, executor, MAX_MESSAGE_SIZE, 0, null, null, null));

        // Both items and the half-close arrive while the dispatch task is held
        ch.writeInbound(requestHeaders("/test.Health/Bidi", new DefaultHttp2Headers()));
        ByteBuf first = WireFrameCodec.encode(REQUEST, ch.alloc());
        ch.writeInbound(new DefaultHttp2DataFrame(first, false));
        ByteBuf second = WireFrameCodec.encode(REQUEST, ch.alloc());
        ch.writeInbound(new DefaultHttp2DataFrame(second, true));

        assertTrue(received.isEmpty(), "the handler has not been dispatched yet");
        assertTrue(ch.outboundMessages().isEmpty(),
                "nothing should be written before the handler runs");

        // Now let the handler subscribe: everything queued earlier must replay
        executor.release();

        assertEquals(2, received.size(),
                "both items published before the subscription must be delivered");
    }

    @Test
    void responseHeadersAreWrittenBeforeTheFirstEchoFrame() {
        List<Message> received = new CopyOnWriteArrayList<>();
        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register("test.Health", "Bidi", countingBidi(received));
        ParkingExecutor executor = new ParkingExecutor();
        EmbeddedChannel ch = new EmbeddedChannel(new WireStreamServerHandler(
                new WireCallDispatcher.HandlerCallDispatcher(registry, Set.of()),
                null, executor, MAX_MESSAGE_SIZE, 0, null, null, null));

        ch.writeInbound(requestHeaders("/test.Health/Bidi", new DefaultHttp2Headers()));
        ch.writeInbound(new DefaultHttp2DataFrame(WireFrameCodec.encode(REQUEST, ch.alloc()), true));

        executor.release();
        // Response frames are committed on the stream's event loop (see
        // WireStreamServerHandler#commitFrame), so the release above only
        // enqueues them; drain the loop for the DATA and trailers to be written.
        ch.runPendingTasks();

        List<String> frameKinds = new ArrayList<>();
        Object outbound;
        while ((outbound = ch.readOutbound()) != null) {
            if (outbound instanceof Http2HeadersFrame headersFrame) {
                frameKinds.add(headersFrame.headers().get(WireConstants.GRPC_STATUS) == null
                        ? "headers" : "trailers");
            } else if (outbound instanceof Http2DataFrame) {
                frameKinds.add("data");
            }
        }

        assertEquals(List.of("headers", "data", "trailers"), frameKinds,
                "a client must never see a response frame ahead of the initial HEADERS");
    }
}
