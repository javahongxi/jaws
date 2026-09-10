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
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.wire.reflection.ServerReflectionRequest;
import org.hongxi.jaws.wire.reflection.ServerReflectionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

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
 *   <li>Streaming dispatch: subscribe to a {@link StreamSource} and emit each
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
    private boolean bidiStream;

    /** True when the resolved path is a client-streaming method. */
    private boolean clientStream;

    /** Thread-safe observer bridging event loop → handler thread for streaming request items. */
    private StreamSubject<Object> streamRequestObserver;

    /** Parser for decoding streaming request items; resolved after path resolution. */
    private Parser<? extends Message> streamRequestParser;

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

        // Check if the resolved method is streaming (bidi or client-streaming)
        if (!reflectionPath) {
            bidiStream = dispatcher.isBidiStream();
            clientStream = dispatcher.isClientStream();
            if (bidiStream || clientStream) {
                streamRequestParser = dispatcher.getRequestStreamParser();
            }
        }

        // Non-reflection paths require END_STREAM to carry the request payload
        // (unless streaming, where frames arrive incrementally)
        if (endStream && !reflectionPath && !bidiStream && !clientStream) {
            sendError(ctx, WireConstants.STATUS_INTERNAL, "Missing request payload");
        }
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            // For streaming, frames arrive incrementally and must
            // not be blocked by the 'dispatched' flag (which is set after the
            // first frame triggers async dispatch). Only unary/server-streaming
            // use 'dispatched' as a one-shot guard.
            if (!bidiStream && !clientStream && (dispatched || rejected)) {
                return;
            }
            if ((bidiStream || clientStream) && rejected) {
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

            // Business streaming: extract frames incrementally
            if (bidiStream || clientStream) {
                processStreamFrames(ctx);
                if (dataFrame.isEndStream() && streamRequestObserver != null) {
                    streamRequestObserver.onCompleted();
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
     * Subscribe to the streaming source and write each emitted protobuf
     * {@link Message} as a gRPC DATA frame. On completion, send trailers.
     * Non-Message items are logged and skipped. Emission stops early when
     * the caller cancels the stream or the deadline expires.
     * <p>
     * The source is first bridged through a {@link StreamSubject}
     * buffer that subscribes immediately. This guards against hot sources
     * that emit items before the network subscriber is attached — a common
     * race in bidirectional streaming where the business method starts
     * producing responses as soon as request items arrive, before the
     * framework has returned the source and subscribed to it for network
     * forwarding.
     */
    protected void dispatchStream(ChannelHandlerContext ctx, StreamSource<?> source) {
        // Bridge the source into a buffered StreamSubject immediately.
        // This ensures items emitted before the network subscriber attaches
        // are captured rather than dropped (hot source protection).
        StreamSubject<Object> buffer = new StreamSubject<>();
        //noinspection unchecked
        StreamSource<Object> typedSource = (StreamSource<Object>) source;
        typedSource.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                buffer.onNext(item);
            }

            @Override
            public void onError(Throwable throwable) {
                buffer.onError(throwable);
            }

            @Override
            public void onCompleted() {
                buffer.onCompleted();
            }
        });

        // Now subscribe the network-forwarding observer to the buffer.
        // The buffer replays all items from the beginning, including any
        // that were already produced by the source before this point.
        buffer.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                if (canceled || !ctx.channel().isActive()) {
                    return;
                }
                // Honor the caller's deadline: stop emitting and report
                // DEADLINE_EXCEEDED once the grpc-timeout window has passed
                if (isDeadlineExceeded()) {
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
            public void onCompleted() {
                if (!canceled && ctx.channel().isActive()) {
                    sendTrailers(ctx, WireConstants.STATUS_OK, null);
                }
            }
        });
    }

    /**
     * Extract complete gRPC frames from the accumulator for bidirectional
     * or client streaming. The first frame triggers dispatch after being
     * added to the request observer; subsequent frames are fed directly.
     */
    private void processStreamFrames(ChannelHandlerContext ctx) {
        while (accumulator != null && accumulator.readableBytes() >= WireConstants.GRPC_HEADER_SIZE) {
            ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
            if (frame == null) {
                break;
            }
            if (!dispatched) {
                dispatched = true;
                streamRequestObserver = new StreamSubject<>();
                // Decode the first frame and add it to the observer BEFORE
                // dispatch so the handler receives ALL items through the stream
                try {
                    Object firstItem;
                    if (streamRequestParser != null) {
                        firstItem = WireFrameCodec.decode(frame, streamRequestParser, requestEncoding);
                    } else {
                        firstItem = WireFrameCodec.extractPayload(frame, requestEncoding);
                    }
                    streamRequestObserver.onNext(firstItem);
                } catch (Exception e) {
                    log.error("Failed to decode first bidi stream item", e);
                    streamRequestObserver.onError(e);
                    frame.release();
                    return;
                } finally {
                    frame.release();
                }
                // Dispatch on the business executor (not the event loop) so that
                // the handler thread is free to block (e.g. CompletableFuture.get)
                // while waiting for the request stream to complete.
                try {
                    serverExecutor.execute(() -> {
                        try {
                            if (clientStream) {
                                dispatcher.dispatchClientStream(ctx, null, this, streamRequestObserver);
                            } else {
                                dispatcher.dispatchBidiStream(ctx, null, this, streamRequestObserver);
                            }
                        } catch (Exception e) {
                            log.error("unexpected stream dispatch error: path={}", path, e);
                            if (!canceled && ctx.channel().isActive()) {
                                sendError(ctx, WireConstants.STATUS_INTERNAL, "Unexpected error: " + e.getMessage());
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    rejectCall(ctx, e);
                }
            } else {
                // Feed subsequent frames to the request observer
                try {
                    Object item;
                    if (streamRequestParser != null) {
                        item = WireFrameCodec.decode(frame, streamRequestParser, requestEncoding);
                    } else {
                        item = WireFrameCodec.extractPayload(frame, requestEncoding);
                    }
                    streamRequestObserver.onNext(item);
                } catch (Exception e) {
                    log.error("Failed to decode bidi stream item", e);
                    streamRequestObserver.onError(e);
                } finally {
                    frame.release();
                }
            }
        }
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
     * Netty surfaces an inbound RST_STREAM to a stream channel as a user event —
     * never as an inbound message — so this is the only place where the reset's
     * error code can be read. {@link #channelInactive} follows within the same
     * millisecond because the codec closes the stream channel, and that close is
     * what has been keeping caller cancellation working all along; handling the
     * event here records <em>why</em> the call ended instead of leaving it to the
     * side effect. A stream reset never reaches this handler as a message, on this
     * or the client side, so no {@code channelRead} branch should try to match it.
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof Http2ResetFrame reset) {
            canceled = true;
            log.info("gRPC stream cancelled by the caller: path={}, errorCode={}",
                    path, reset.errorCode());
            return;
        }
        super.userEventTriggered(ctx, evt);
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
