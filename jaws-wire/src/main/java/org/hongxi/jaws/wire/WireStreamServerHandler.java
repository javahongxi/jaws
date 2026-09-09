package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.util.ReferenceCountUtil;
import org.hongxi.jaws.wire.reflection.ServerReflectionRequest;
import org.hongxi.jaws.wire.reflection.ServerReflectionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;

/**
 * Per-stream inbound handler for the gRPC server, owning the gRPC wire
 * mechanics shared by both dispatch modes:
 * <ol>
 *   <li>{@code channelRead} frame dispatch (HEADERS / DATA / RST_STREAM)</li>
 *   <li>DATA frame accumulation with the max-inbound-message-size guard and
 *       gRPC frame extraction via {@link WireFrameCodec}</li>
 *   <li>Protocol header parsing: {@code grpc-timeout} (deadline enforcement),
 *       {@code grpc-encoding} (inbound message compression), and custom
 *       metadata ({@link WireMetadata})</li>
 *   <li>Response writing: initial HEADERS ({@code :status 200} + content-type),
 *       DATA frames, and trailers carrying grpc-status / grpc-message; errors
 *       raised before any response body use the trailers-only form</li>
 *   <li>Streaming dispatch: subscribe to a {@link Flow.Publisher} and emit each
 *       protobuf {@link Message} as a gRPC DATA frame, honoring the deadline
 *       and caller cancellation (RST_STREAM)</li>
 *   <li>Lifecycle: buffer release on early close, error trailers, channel close</li>
 * </ol>
 * <p>
 * The two dispatch modes differ only in path resolution and business invocation,
 * which are delegated to a {@link WireCallDispatcher} strategy:
 * <ul>
 *   <li>{@link WireCallDispatcher.HandlerCallDispatcher} — direct API mode,
 *       routes via {@link WireHandlerRegistry} to typed {@link WireMethodHandler}</li>
 *   <li>{@link WireCallDispatcher.ProviderCallDispatcher} — Provider pipeline mode,
 *       bridges to the Jaws {@link org.hongxi.jaws.transport.MessageHandler} pipeline</li>
 * </ul>
 * This composition simplifies adding new dispatch modes (e.g. a future
 * port-unification router serving both jaws-HTTP/2 and gRPC streams on
 * one port) without extending the handler hierarchy.
 *
 * @author shenhongxi
 * @see WireCallDispatcher
 */
public class WireStreamServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireStreamServerHandler.class);

    /** Dispatch strategy: registry-based routing or SPI pipeline bridge. */
    private final WireCallDispatcher dispatcher;
    /** Reflection service instance; null if reflection is not enabled. */
    private final WireReflectionService reflectionService;
    protected final ExecutorService serverExecutor;
    /** Max size of a single inbound gRPC message in bytes. */
    protected final int maxMessageSize;
    /** Max size of inbound HTTP/2 headers (metadata) in bytes. */
    private final int maxInboundMetadataSize;
    /**
     * Server-configured compression (identity or gzip); downgraded to
     * identity per call when the client's grpc-accept-encoding does not
     * advertise it.
     */
    protected String compression;

    protected String path;
    /** Absolute caller deadline in epoch ms parsed from grpc-timeout; 0 = none. */
    protected long deadlineMs;
    /** Inbound message encoding declared by the grpc-encoding header. */
    protected String requestEncoding = WireConstants.ENCODING_IDENTITY;
    /** Custom metadata (non-reserved request headers) for the current call. */
    protected Map<String, String> attachments = Map.of();

    protected ByteBuf accumulator;
    /** Set when the inbound message exceeded {@link #maxMessageSize}. */
    private boolean rejected;
    protected boolean dispatched;

    /** The stream channel context, set on first channelRead. */
    private ChannelHandlerContext streamCtx;

    /** Set when the caller canceled the stream (RST_STREAM) or it closed. */
    protected volatile boolean canceled;

    /** True when the path is the reflection bidi-stream; frames are processed individually. */
    private boolean reflectionPath;

    /** True when the resolved path is a bidirectional streaming method. */
    private boolean biStreaming;

    /** Thread-safe publisher bridging event loop → handler thread for bidi request items. */
    private BufferedRequestPublisher biStreamRequestPublisher;

    /** Parser for decoding bidi request stream items; resolved after path resolution. */
    private Parser<? extends Message> biStreamRequestParser;

    /**
     * Whether the initial response HEADERS frame has been written to the
     * stream; once set, subsequent errors must use trailers rather than
     * the trailers-only form.
     */
    private boolean responseHeadersSent;

    WireStreamServerHandler(WireCallDispatcher dispatcher,
                            WireReflectionService reflectionService,
                            ExecutorService serverExecutor,
                            int maxMessageSize, int maxInboundMetadataSize, String compression) {
        this.dispatcher = dispatcher;
        this.reflectionService = reflectionService;
        this.serverExecutor = serverExecutor;
        this.maxMessageSize = maxMessageSize;
        this.maxInboundMetadataSize = maxInboundMetadataSize;
        this.compression = compression;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // Notify the connection lifecycle handler (if present) that a new
        // stream has been opened on this connection
        if (ctx.channel().parent() != null) {
            WireConnectionLifecycleHandler lifecycle =
                    ctx.channel().parent().pipeline().get(WireConnectionLifecycleHandler.class);
            if (lifecycle != null) {
                lifecycle.streamOpened();
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (streamCtx == null) {
            streamCtx = ctx;
        }
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                onHeaders(ctx, headersFrame);
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else if (msg instanceof Http2ResetFrame) {
                // Caller canceled the call (grpc-java Context cancellation):
                // stop producing; the stream channel closes automatically.
                canceled = true;
                ReferenceCountUtil.release(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            log.error("stream error: path={}", path, e);
            sendError(ctx, WireConstants.STATUS_INTERNAL, "Internal error: " + e.getMessage());
        }
    }

    private void onHeaders(ChannelHandlerContext ctx, Http2HeadersFrame headersFrame) {
        Http2Headers headers = headersFrame.headers();
        boolean endStream = headersFrame.isEndStream();

        // Defense-in-depth: reject oversized metadata even though the HTTP/2
        // codec already enforces SETTINGS_MAX_HEADER_LIST_SIZE
        if (maxInboundMetadataSize > 0 && WireMetadata.estimateHeaderSize(headers) > maxInboundMetadataSize) {
            sendError(ctx, WireConstants.STATUS_RESOURCE_EXHAUSTED,
                    "gRPC request metadata exceeds maxInboundMetadataSize: " + maxInboundMetadataSize);
            return;
        }

        path = Objects.toString(headers.path(), null);

        if (path == null) {
            sendError(ctx, WireConstants.STATUS_UNIMPLEMENTED, "Missing :path header");
            return;
        }

        // Parse the caller's deadline (gRPC timeout propagation)
        CharSequence timeoutSeq = headers.get(WireStatus.GRPC_TIMEOUT);
        if (timeoutSeq != null) {
            long timeoutMs = WireStatus.decodeTimeout(timeoutSeq.toString());
            if (timeoutMs < 0) {
                log.warn("malformed grpc-timeout header: {}", timeoutSeq);
            } else if (timeoutMs > 0) {
                deadlineMs = System.currentTimeMillis() + timeoutMs;
            }
        }

        // Parse the inbound message encoding; an unsupported encoding must be
        // rejected with UNIMPLEMENTED per the gRPC spec
        CharSequence encodingSeq = headers.get(WireConstants.GRPC_ENCODING);
        if (encodingSeq != null) {
            String encoding = encodingSeq.toString();
            if (!WireCompression.isSupported(encoding)) {
                sendError(ctx, WireConstants.STATUS_UNIMPLEMENTED,
                        "Unsupported grpc-encoding: " + encoding);
                return;
            }
            requestEncoding = encoding;
        }

        // Custom metadata: non-reserved headers → call attachments
        attachments = WireMetadata.fromHeaders(headers);

        // Downgrade the response encoding when the client does not accept it
        if (compression != null && !WireConstants.ENCODING_IDENTITY.equals(compression)) {
            CharSequence acceptSeq = headers.get(WireConstants.GRPC_ACCEPT_ENCODING);
            if (acceptSeq == null) {
                compression = WireConstants.ENCODING_IDENTITY;
            } else {
                for (String candidate : acceptSeq.toString().split(",")) {
                    if (candidate.trim().equals(compression)) {
                        compression = WireConstants.ENCODING_IDENTITY;
                        break;
                    }
                }
            }
        }

        // Reflection is a bidirectional stream handled at the stream-handler
        // level (like health check in Provider mode). It bypasses the
        // dispatcher's path resolution entirely.
        reflectionPath = WireReflectionService.REFLECTION_PATH.equals(path)
                && reflectionService != null;

        if (!reflectionPath && !dispatcher.resolvePath(ctx, path)) {
            sendError(ctx, WireConstants.STATUS_NOT_FOUND, "Method not found: " + path);
            return;
        }

        // Check if the resolved method is bidirectional streaming
        if (!reflectionPath) {
            biStreaming = dispatcher.isBiStreaming();
            if (biStreaming) {
                biStreamRequestParser = dispatcher.getBiStreamRequestParser();
            }
        }

        // Non-reflection paths require END_STREAM to carry the request payload
        // (unless bidirectional streaming, where frames arrive incrementally)
        if (endStream && !reflectionPath && !biStreaming) {
            sendError(ctx, WireConstants.STATUS_INTERNAL, "Missing request payload");
        }
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            // For bidirectional streaming, frames arrive incrementally and must
            // not be blocked by the 'dispatched' flag (which is set after the
            // first frame triggers async dispatch). Only unary/server-streaming
            // use 'dispatched' as a one-shot guard.
            if (!biStreaming && (dispatched || rejected)) {
                return;
            }
            if (biStreaming && rejected) {
                return;
            }

            ByteBuf content = dataFrame.content();
            if (accumulator == null) {
                accumulator = ctx.alloc().buffer(content.readableBytes());
            }
            accumulator.writeBytes(content);

            // Guard against oversized messages (default 4MiB, same as grpc-java):
            // fail the call instead of buffering unbounded data
            if (accumulator.readableBytes() > maxMessageSize + WireConstants.GRPC_HEADER_SIZE) {
                rejected = true;
                accumulator.release();
                accumulator = null;
                sendError(ctx, WireConstants.STATUS_RESOURCE_EXHAUSTED,
                        "gRPC message exceeds maxInboundMessageSize: " + maxMessageSize);
                return;
            }

            // Reflection is a bidirectional stream: process each gRPC frame
            // immediately and respond without waiting for END_STREAM.
            if (reflectionPath) {
                processReflectionFrames(ctx);
                return;
            }

            // Business bidirectional streaming: extract frames incrementally
            if (biStreaming) {
                processBiStreamFrames(ctx);
                if (dataFrame.isEndStream()) {
                    completeBiRequestStream();
                }
                return;
            }

            if (dataFrame.isEndStream()) {
                dispatch(ctx);
            }
        } finally {
            dataFrame.release();
        }
    }

    /**
     * Process complete gRPC frames from the accumulator for the reflection
     * bidi-stream. Each frame is decoded as a {@link ServerReflectionRequest},
     * handled by the {@link WireReflectionService}, and the response is written
     * immediately — no waiting for END_STREAM.
     */
    private void processReflectionFrames(ChannelHandlerContext ctx) {
        while (accumulator != null && accumulator.readableBytes() >= WireConstants.GRPC_HEADER_SIZE) {
            ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
            if (frame == null) {
                break; // incomplete frame, wait for more data
            }
            try {
                ServerReflectionRequest request = WireFrameCodec.decode(
                        frame, WireReflectionService.getRequestParser(), requestEncoding);
                ServerReflectionResponse response = reflectionService.handleRequest(request);
                sendResponseHeaders(ctx);
                ByteBuf responseFrame = WireFrameCodec.encode(response, ctx.alloc(), compression);
                ctx.writeAndFlush(new DefaultHttp2DataFrame(responseFrame, false));
            } catch (InvalidProtocolBufferException e) {
                log.error("Reflection request decode failed", e);
                sendError(ctx, WireConstants.STATUS_INTERNAL,
                        "Invalid reflection request: " + e.getMessage());
                return;
            } finally {
                frame.release();
            }
        }
    }

    /**
     * Hand the complete accumulated payload to the business executor via the
     * {@link WireCallDispatcher}, which decodes the request, invokes the
     * handler, and writes the response.
     *
     * @param ctx the stream channel context
     */
    private void dispatch(ChannelHandlerContext ctx) {
        if (dispatched) {
            return;
        }
        dispatched = true;

        final ByteBuf frameData = this.accumulator;

        try {
            serverExecutor.execute(() -> {
                try {
                    dispatcher.dispatch(ctx, frameData, this);
                } catch (Exception e) {
                    log.error("unexpected dispatch error: path={}", path, e);
                    if (!canceled && ctx.channel().isActive()) {
                        sendError(ctx, WireConstants.STATUS_INTERNAL,
                                "Unexpected error: " + e.getMessage());
                    }
                } finally {
                    if (frameData != null) {
                        frameData.release();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            rejectCall(ctx, e);
        }
    }

    /**
     * Fail the call when the business executor rejects the dispatch task
     * (thread pool full, see AbortPolicyWithStats). Reports UNAVAILABLE so
     * standard gRPC clients see retryable semantics, and releases the
     * accumulated request buffer that the rejected task will never consume.
     */
    protected void rejectCall(ChannelHandlerContext ctx, RejectedExecutionException e) {
        log.error("request rejected due to full thread pool: path={}", path);
        if (accumulator != null) {
            accumulator.release();
            accumulator = null;
        }
        sendError(ctx, WireConstants.STATUS_UNAVAILABLE,
                "Request rejected: server thread pool is full");
    }

    /**
     * Subscribe to the streaming publisher and write each emitted protobuf
     * {@link Message} as a gRPC DATA frame. On completion, send trailers.
     * Non-Message items are logged and skipped. Emission stops early when
     * the caller cancels the stream or the deadline expires.
     */
    protected void dispatchStream(ChannelHandlerContext ctx, Flow.Publisher<?> publisher) {
        publisher.subscribe(new Flow.Subscriber<Object>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Object item) {
                if (canceled || !ctx.channel().isActive()) {
                    subscription.cancel();
                    return;
                }
                // Honor the caller's deadline: stop emitting and report
                // DEADLINE_EXCEEDED once the grpc-timeout window has passed
                if (isDeadlineExceeded()) {
                    subscription.cancel();
                    sendTrailers(ctx, WireConstants.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
                    return;
                }
                if (item instanceof Message msg) {
                    sendResponseHeaders(ctx);
                    ByteBuf responseFrame = WireFrameCodec.encode(msg, ctx.alloc(), compression);
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(responseFrame, false));
                } else {
                    log.warn("streaming: expected protobuf Message but got: {}",
                            item != null ? item.getClass().getName() : "null");
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("streaming error: path={}", path, throwable);
                if (!canceled && ctx.channel().isActive()) {
                    // Map failure class to grpc-status (retryable/deadline semantics)
                    sendTrailers(ctx, WireStatus.fromThrowable(throwable),
                            "Stream failed: " + throwable.getMessage());
                }
            }

            @Override
            public void onComplete() {
                if (!canceled && ctx.channel().isActive()) {
                    sendTrailers(ctx, WireConstants.STATUS_OK, null);
                }
            }
        });
    }

    /**
     * Extract complete gRPC frames from the accumulator for bidirectional
     * streaming. The first frame triggers dispatch after being added to the
     * request publisher; subsequent frames are fed directly.
     */
    private void processBiStreamFrames(ChannelHandlerContext ctx) {
        while (accumulator != null && accumulator.readableBytes() >= WireConstants.GRPC_HEADER_SIZE) {
            ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
            if (frame == null) {
                break;
            }
            if (!dispatched) {
                dispatched = true;
                biStreamRequestPublisher = new BufferedRequestPublisher(serverExecutor);
                // Decode the first frame and add it to the publisher BEFORE
                // dispatch so the handler receives ALL items through the stream
                try {
                    Object firstItem;
                    if (biStreamRequestParser != null) {
                        firstItem = WireFrameCodec.decode(frame, biStreamRequestParser, requestEncoding);
                    } else {
                        firstItem = WireFrameCodec.extractPayload(frame, requestEncoding);
                    }
                    biStreamRequestPublisher.addItem(firstItem);
                } catch (Exception e) {
                    log.error("Failed to decode first bidi stream item", e);
                    biStreamRequestPublisher.completeExceptionally(e);
                    frame.release();
                    return;
                } finally {
                    frame.release();
                }
                // Dispatch on the business executor (not the event loop) so that
                // SubmissionPublisher.subscribe() drains correctly on a non-event-loop thread.
                // This mirrors how unary/stream dispatch() uses serverExecutor.execute().
                try {
                    serverExecutor.execute(() -> {
                        try {
                            dispatcher.dispatchBiStream(ctx, null, this, biStreamRequestPublisher);
                        } catch (Exception e) {
                            log.error("unexpected bidi dispatch error: path={}", path, e);
                            if (!canceled && ctx.channel().isActive()) {
                                sendError(ctx, WireConstants.STATUS_INTERNAL, "Unexpected error: " + e.getMessage());
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    rejectCall(ctx, e);
                }
            } else {
                // Feed subsequent frames to the request publisher
                try {
                    Object item;
                    if (biStreamRequestParser != null) {
                        item = WireFrameCodec.decode(frame, biStreamRequestParser, requestEncoding);
                    } else {
                        byte[] payload = WireFrameCodec.extractPayload(frame, requestEncoding);
                        item = payload;
                    }
                    biStreamRequestPublisher.addItem(item);
                } catch (Exception e) {
                    log.error("Failed to decode bidi stream item", e);
                    biStreamRequestPublisher.completeExceptionally(e);
                } finally {
                    frame.release();
                }
            }
        }
    }

    /**
     * Signal that the client has finished sending request items (END_STREAM received).
     */
    private void completeBiRequestStream() {
        if (biStreamRequestPublisher != null) {
            biStreamRequestPublisher.complete();
        }
    }

    /**
     * Resolve the request parser for bidi stream item decoding. For the
     * handler mode, the parser comes from the resolved handler. For the
     * provider mode, returns null (the WireMessageHandler handles conversion).
     */
    private com.google.protobuf.Parser<? extends Message> getRequestParserForBiStream() {
        return biStreamRequestParser;
    }

    /**
     * Send a unary response: check cancellation and deadline, then write
     * initial HEADERS, the encoded gRPC DATA frame, and trailers.
     * Called by the {@link WireCallDispatcher} after a successful invocation.
     */
    protected void sendUnaryResponse(ChannelHandlerContext ctx, Message response) {
        if (canceled || !ctx.channel().isActive()) {
            return;
        }
        if (isDeadlineExceeded()) {
            sendError(ctx, WireConstants.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
            return;
        }
        sendResponseHeaders(ctx);
        ByteBuf responseFrame = WireFrameCodec.encode(response, ctx.alloc(), compression);
        ctx.write(new DefaultHttp2DataFrame(responseFrame, false));
        sendTrailers(ctx, WireConstants.STATUS_OK, null);
    }

    /**
     * Send the initial response HEADERS with :status 200, content-type, and
     * the encoding capabilities advertised for follow-up messages on this
     * connection.
     */
    protected void sendResponseHeaders(ChannelHandlerContext ctx) {
        if (responseHeadersSent) {
            return;
        }
        responseHeadersSent = true;
        Http2Headers headers = new DefaultHttp2Headers()
                .status("200")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.GRPC_ACCEPT_ENCODING, WireConstants.ACCEPT_ENCODINGS);
        if (compression != null
                && !WireConstants.ENCODING_IDENTITY.equals(compression)) {
            headers.set(WireConstants.GRPC_ENCODING, compression);
        }
        ctx.write(new DefaultHttp2HeadersFrame(headers, false));
    }

    protected void sendTrailers(ChannelHandlerContext ctx, int status, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        // Ensure initial response HEADERS are sent before trailers
        sendResponseHeaders(ctx);
        Http2Headers trailers = new DefaultHttp2Headers()
                .set(WireConstants.GRPC_STATUS, String.valueOf(status));
        if (message != null) {
            trailers.set(WireConstants.GRPC_MESSAGE, message);
        }
        // Rich error model: include grpc-status-details-bin for non-OK statuses
        if (status != WireConstants.STATUS_OK) {
            WireErrorDetails.writeToTrailers(trailers, status, message);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));
    }

    /**
     * Report an error. When no response body has been sent yet, use the
     * trailers-only form (a single HEADERS frame with END_STREAM carrying
     * the status), as standard gRPC implementations do for immediate failures.
     */
    protected void sendError(ChannelHandlerContext ctx, int status, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        if (responseHeadersSent) {
            sendTrailers(ctx, status, message);
            return;
        }
        // Trailers-only response: status + content-type + grpc-status in one frame
        Http2Headers trailersOnly = new DefaultHttp2Headers()
                .status("200")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.GRPC_STATUS, String.valueOf(status));
        if (message != null) {
            trailersOnly.set(WireConstants.GRPC_MESSAGE, message);
        }
        // Rich error model for trailers-only errors too
        if (status != WireConstants.STATUS_OK) {
            WireErrorDetails.writeToTrailers(trailersOnly, status, message);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailersOnly, true));
    }

    /**
     * @return true if the caller's deadline (grpc-timeout) has passed
     */
    protected boolean isDeadlineExceeded() {
        return deadlineMs > 0 && System.currentTimeMillis() >= deadlineMs;
    }

    /**
     * @return the remaining deadline in ms, or 0 when no deadline is set or
     *         it already expired
     */
    protected long remainingDeadlineMs() {
        if (deadlineMs <= 0) {
            return 0;
        }
        return Math.max(0, deadlineMs - System.currentTimeMillis());
    }

    /**
     * Thread-safe {@link Flow.Publisher} that bridges the Netty event loop
     * (which produces bidi request items) with the business handler thread
     * (which subscribes and consumes them). Items submitted before subscription
     * are buffered and drained on subscribe.
     */
    static final class BufferedRequestPublisher implements Flow.Publisher<Object> {
        private final java.util.List<Object> buffer = new java.util.ArrayList<>();
        private final java.util.concurrent.Executor drainExecutor;
        private Flow.Subscriber<? super Object> subscriber;
        private boolean completed;
        private Throwable error;

        BufferedRequestPublisher(java.util.concurrent.Executor drainExecutor) {
            this.drainExecutor = drainExecutor;
        }

        synchronized void addItem(Object item) {
            if (completed || error != null) return;
            if (subscriber != null) {
                subscriber.onNext(item);
            } else {
                buffer.add(item);
            }
        }

        synchronized void complete() {
            if (completed) return;
            completed = true;
            if (subscriber != null) {
                subscriber.onComplete();
            }
        }

        synchronized void completeExceptionally(Throwable t) {
            if (completed || error != null) return;
            error = t;
            if (subscriber != null) {
                subscriber.onError(t);
            }
        }

        @Override
        public void subscribe(Flow.Subscriber<? super Object> s) {
            java.util.List<Object> toDrain;
            Throwable err;
            boolean done;
            synchronized (this) {
                this.subscriber = s;
                toDrain = new java.util.ArrayList<>(buffer);
                buffer.clear();
                err = this.error;
                done = this.completed;
            }
            s.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) { /* unbounded */ }
                @Override
                public void cancel() { /* best-effort */ }
            });
            // Drain buffered items asynchronously to avoid reentrancy issues.
            // When the subscriber's onNext handler submits to a SubmissionPublisher,
            // that publisher's subscribe/drain chain must not nest inside this
            // subscribe call — doing so causes the first item to be silently
            // dropped by SubmissionPublisher's drain-task scheduling.
            if (!toDrain.isEmpty() || err != null || done) {
                drainExecutor.execute(() -> {
                    for (Object item : toDrain) {
                        s.onNext(item);
                    }
                    if (err != null) {
                        s.onError(err);
                    } else if (done) {
                        s.onComplete();
                    }
                });
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        canceled = true;
        notifyStreamClosed(ctx);
        // Release accumulated buffer if the stream closed before dispatch
        if (accumulator != null && !dispatched) {
            accumulator.release();
            accumulator = null;
        }
    }

    /**
     * Notify the connection lifecycle handler that this stream has closed.
     * Safe to call multiple times; the lifecycle handler tracks the count.
     */
    private void notifyStreamClosed(ChannelHandlerContext ctx) {
        if (ctx.channel().parent() == null) {
            return;
        }
        WireConnectionLifecycleHandler lifecycle =
                ctx.channel().parent().pipeline().get(WireConnectionLifecycleHandler.class);
        if (lifecycle != null) {
            lifecycle.streamClosed();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("stream exception: path={}", path, cause);
        if (!dispatched) {
            sendError(ctx, WireConstants.STATUS_INTERNAL, cause.getMessage());
        }
        ctx.close();
    }

    /**
     * @return the stream channel context, available after the first channelRead
     */
    ChannelHandlerContext ctx() {
        return streamCtx;
    }
}
