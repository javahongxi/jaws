package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
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
 *   <li>{@link WireCallDispatcher.WireRegistryCallDispatcher} — direct API mode,
 *       routes via {@link WireServiceRegistry} to typed {@link WireMethodHandler}</li>
 *   <li>{@link WireCallDispatcher.WireSpiCallDispatcher} — SPI adapter mode,
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
    protected final ExecutorService serverExecutor;
    /** Max size of a single inbound gRPC message in bytes. */
    protected final int maxMessageSize;
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
    protected Map<String, String> attachments = Collections.emptyMap();

    protected ByteBuf accumulator;
    /** Set when the inbound message exceeded {@link #maxMessageSize}. */
    private boolean rejected;
    protected boolean dispatched;

    /** Set when the caller canceled the stream (RST_STREAM) or it closed. */
    protected volatile boolean canceled;

    /**
     * Whether the initial response HEADERS frame has been written to the
     * stream; once set, subsequent errors must use trailers rather than
     * the trailers-only form.
     */
    private boolean responseHeadersSent;

    WireStreamServerHandler(WireCallDispatcher dispatcher,
                            ExecutorService serverExecutor,
                            int maxMessageSize, String compression) {
        this.dispatcher = dispatcher;
        this.serverExecutor = serverExecutor;
        this.maxMessageSize = maxMessageSize;
        this.compression = compression;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
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

        if (!dispatcher.resolvePath(ctx, path)) {
            sendError(ctx, WireConstants.STATUS_NOT_FOUND, "Method not found: " + path);
            return;
        }

        if (endStream) {
            sendError(ctx, WireConstants.STATUS_INTERNAL, "Missing request payload");
        }
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            if (dispatched || rejected) {
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
                sendError(ctx, WireStatus.STATUS_RESOURCE_EXHAUSTED,
                        "gRPC message exceeds maxInboundMessageSize: " + maxMessageSize);
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
        sendError(ctx, WireStatus.STATUS_UNAVAILABLE,
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
                    sendTrailers(ctx, WireStatus.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
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
     * Send a unary response: check cancellation and deadline, then write
     * initial HEADERS, the encoded gRPC DATA frame, and trailers.
     * Called by the {@link WireCallDispatcher} after a successful invocation.
     */
    protected void sendUnaryResponse(ChannelHandlerContext ctx, Message response) {
        if (canceled || !ctx.channel().isActive()) {
            return;
        }
        if (isDeadlineExceeded()) {
            sendError(ctx, WireStatus.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
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

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        canceled = true;
        // Release accumulated buffer if the stream closed before dispatch
        if (accumulator != null && !dispatched) {
            accumulator.release();
            accumulator = null;
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
}
