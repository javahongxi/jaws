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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;

/**
 * Base class for per-stream inbound handlers on the gRPC server, owning the
 * gRPC wire mechanics shared by both dispatch modes:
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
 * Subclasses implement the two points where dispatch semantics differ:
 * <ul>
 *   <li>{@link #onHeadersResolved(ChannelHandlerContext, Http2Headers, String, boolean)} —
 *       route the {@code :path} (registry lookup for the direct API mode,
 *       service/method name parsing for the SPI adapter mode)</li>
 *   <li>{@link #dispatch(ChannelHandlerContext)} — hand the complete accumulated
 *       payload to the business executor and write the response</li>
 * </ul>
 * <p>
 * Because both modes share this class, the wire protocol has a single stream
 * handler family; a future port-unification router (one port serving both
 * jaws-HTTP/2 and gRPC streams) can dispatch to it without knowing which
 * mode is active.
 *
 * @author shenhongxi
 */
abstract class AbstractWireStreamHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(AbstractWireStreamHandler.class);

    /** Log message prefix identifying the concrete handler in error logs. */
    private final String logPrefix;

    protected final ExecutorService serverExecutor;

    /** Max size of a single inbound gRPC message in bytes. */
    protected final int maxMessageSize;

    /**
     * Outbound encoding for response messages as configured (identity or gzip);
     * downgraded to identity per call when the client's grpc-accept-encoding
     * does not advertise it.
     */
    protected String responseEncoding;

    protected String path;
    protected ByteBuf accumulator;
    protected boolean dispatched;
    /** Set when the caller canceled the stream (RST_STREAM) or it closed. */
    protected volatile boolean canceled;
    /** Set when the inbound message exceeded {@link #maxMessageSize}. */
    private boolean rejected;

    /** Absolute caller deadline in epoch ms parsed from grpc-timeout; 0 = none. */
    protected long deadlineMs;
    /** Inbound message encoding declared by the grpc-encoding header. */
    protected String requestEncoding = WireConstants.ENCODING_IDENTITY;
    /** Custom metadata (non-reserved request headers) for the current call. */
    protected Map<String, String> attachments = Collections.emptyMap();

    private boolean responseHeadersSent;

    AbstractWireStreamHandler(String logPrefix, ExecutorService serverExecutor,
                              int maxMessageSize, String configuredResponseEncoding) {
        this.logPrefix = logPrefix;
        this.serverExecutor = serverExecutor;
        this.maxMessageSize = maxMessageSize;
        this.responseEncoding = configuredResponseEncoding;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                onHeaders(ctx, headersFrame.headers(), headersFrame.isEndStream());
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else if (msg instanceof Http2ResetFrame) {
                // Caller cancelled the call (grpc-java Context cancellation):
                // stop producing; the stream channel closes automatically.
                canceled = true;
                ReferenceCountUtil.release(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            log.error("{} stream error: path={}", logPrefix, path, e);
            sendError(ctx, WireConstants.STATUS_INTERNAL, "Internal error: " + e.getMessage());
        }
    }

    private void onHeaders(ChannelHandlerContext ctx, Http2Headers headers, boolean endStream) {
        CharSequence pathSeq = headers.path();
        path = pathSeq != null ? pathSeq.toString() : null;

        if (path == null) {
            sendError(ctx, WireConstants.STATUS_UNIMPLEMENTED, "Missing :path header");
            return;
        }

        // Parse the caller's deadline (gRPC timeout propagation)
        CharSequence timeoutSeq = headers.get(WireStatus.GRPC_TIMEOUT);
        if (timeoutSeq != null) {
            long timeoutMs = WireStatus.decodeTimeout(timeoutSeq.toString());
            if (timeoutMs < 0) {
                log.warn("{} malformed grpc-timeout header: {}", logPrefix, timeoutSeq);
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
        if (responseEncoding != null
                && !WireConstants.ENCODING_IDENTITY.equals(responseEncoding)) {
            CharSequence acceptSeq = headers.get(WireConstants.GRPC_ACCEPT_ENCODING);
            if (acceptSeq == null || !containsEncoding(acceptSeq.toString(), responseEncoding)) {
                responseEncoding = WireConstants.ENCODING_IDENTITY;
            }
        }

        onHeadersResolved(ctx, headers, path, endStream);

        if (endStream) {
            sendError(ctx, WireConstants.STATUS_INTERNAL, "Missing request payload");
        }
    }

    /**
     * Whether a comma-separated accept-encoding value contains the encoding.
     */
    private static boolean containsEncoding(String acceptEncodings, String encoding) {
        for (String candidate : acceptEncodings.split(",")) {
            if (candidate.trim().equals(encoding)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle the resolved request HEADERS. Called once, after the path has
     * been extracted and validated and the protocol headers (grpc-timeout,
     * grpc-encoding, metadata) have been parsed, before the "missing payload"
     * check for END_STREAM-only headers. Implementations typically route the
     * path (registry lookup for the direct API mode, service/method name
     * parsing for the SPI adapter mode).
     *
     * @param ctx        the stream channel context
     * @param headers    the full request headers (path included)
     * @param path       the request path (never null here)
     * @param endStream  whether the HEADERS frame already ended the stream
     */
    protected abstract void onHeadersResolved(ChannelHandlerContext ctx, Http2Headers headers,
                                              String path, boolean endStream);

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            if (dispatched || rejected || !acceptsData()) {
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
     * Whether incoming DATA frames should still be accumulated. Returns
     * {@code true} by default; the direct API mode returns {@code false}
     * once the path failed to resolve, so no memory is spent buffering a
     * request that will never be dispatched.
     */
    protected boolean acceptsData() {
        return true;
    }

    /**
     * Hand the complete accumulated payload (in {@link #accumulator}) to the
     * business executor and write the response. Called once, when the request
     * END_STREAM arrives. The implementation owns releasing the accumulator.
     *
     * @param ctx the stream channel context
     */
    protected abstract void dispatch(ChannelHandlerContext ctx);

    /**
     * Fail the call when the business executor rejects the dispatch task
     * (thread pool full, see AbortPolicyWithStats). Reports UNAVAILABLE so
     * standard gRPC clients see retryable semantics, and releases the
     * accumulated request buffer that the rejected task will never consume.
     */
    protected void rejectCall(ChannelHandlerContext ctx, RejectedExecutionException e) {
        log.error("{} request rejected due to full thread pool: path={}", logPrefix, path);
        if (accumulator != null) {
            accumulator.release();
            accumulator = null;
        }
        sendError(ctx, WireStatus.STATUS_UNAVAILABLE,
                "Request rejected: server thread pool is full");
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
                    ByteBuf responseFrame = WireFrameCodec.encode(msg, ctx.alloc(), responseEncoding);
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(responseFrame, false));
                } else {
                    log.warn("{} streaming: expected protobuf Message but got: {}",
                            logPrefix, item != null ? item.getClass().getName() : "null");
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("{} streaming error: path={}", logPrefix, path, throwable);
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
        if (responseEncoding != null
                && !WireConstants.ENCODING_IDENTITY.equals(responseEncoding)) {
            headers.set(WireConstants.GRPC_ENCODING, responseEncoding);
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
        log.error("{} stream exception: path={}", logPrefix, path, cause);
        if (!dispatched) {
            sendError(ctx, WireConstants.STATUS_INTERNAL, cause.getMessage());
        }
        ctx.close();
    }
}
