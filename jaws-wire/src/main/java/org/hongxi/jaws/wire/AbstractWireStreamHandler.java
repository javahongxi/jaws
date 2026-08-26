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
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;

/**
 * Base class for per-stream inbound handlers on the gRPC server, owning the
 * gRPC wire mechanics shared by both dispatch modes:
 * <ol>
 *   <li>{@code channelRead} frame dispatch (HEADERS / DATA / other)</li>
 *   <li>DATA frame accumulation and gRPC frame extraction via {@link WireFrameCodec}</li>
 *   <li>Response writing: initial HEADERS ({@code :status 200} + content-type),
 *       DATA frames, and trailers carrying grpc-status / grpc-message</li>
 *   <li>Streaming dispatch: subscribe to a {@link Flow.Publisher} and emit each
 *       protobuf {@link Message} as a gRPC DATA frame</li>
 *   <li>Lifecycle: buffer release on early close, error trailers, channel close</li>
 * </ol>
 * <p>
 * Subclasses implement the two points where dispatch semantics differ:
 * <ul>
 *   <li>{@link #onHeadersResolved(ChannelHandlerContext, String, boolean)} —
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

    protected final ExecutorService executor;

    protected String path;
    protected ByteBuf accumulator;
    protected boolean dispatched;
    private boolean responseHeadersSent;

    AbstractWireStreamHandler(String logPrefix, ExecutorService executor) {
        this.logPrefix = logPrefix;
        this.executor = executor;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                onHeaders(ctx, headersFrame.headers(), headersFrame.isEndStream());
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            log.error("{} stream error: path={}", logPrefix, path, e);
            sendTrailers(ctx, WireConstants.STATUS_INTERNAL, "Internal error: " + e.getMessage());
        }
    }

    private void onHeaders(ChannelHandlerContext ctx, Http2Headers headers, boolean endStream) {
        CharSequence pathSeq = headers.path();
        path = pathSeq != null ? pathSeq.toString() : null;

        if (path == null) {
            sendTrailers(ctx, WireConstants.STATUS_UNIMPLEMENTED, "Missing :path header");
            return;
        }

        onHeadersResolved(ctx, headers, path, endStream);

        if (endStream) {
            sendTrailers(ctx, WireConstants.STATUS_INTERNAL, "Missing request payload");
        }
    }

    /**
     * Handle the resolved request HEADERS. Called once, after the path has
     * been extracted and validated, before the "missing payload" check for
     * END_STREAM-only headers. Implementations typically route the path and
     * may read protocol headers relevant to dispatch (e.g. grpc-timeout).
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
            if (dispatched || !acceptsData()) {
                return;
            }

            ByteBuf content = dataFrame.content();
            if (accumulator == null) {
                accumulator = ctx.alloc().buffer(content.readableBytes());
            }
            accumulator.writeBytes(content);

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
     * Subscribe to the streaming publisher and write each emitted protobuf
     * {@link Message} as a gRPC DATA frame. On completion, send trailers.
     * Non-Message items are logged and skipped.
     */
    protected void dispatchStreaming(ChannelHandlerContext ctx, Flow.Publisher<?> publisher) {
        publisher.subscribe(new Flow.Subscriber<Object>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Object item) {
                if (!ctx.channel().isActive()) return;
                if (item instanceof Message msg) {
                    sendResponseHeaders(ctx);
                    ByteBuf responseFrame = WireFrameCodec.encode(msg, ctx.alloc());
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(responseFrame, false));
                } else {
                    log.warn("{} streaming: expected protobuf Message but got: {}",
                            logPrefix, item != null ? item.getClass().getName() : "null");
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("{} streaming error: path={}", logPrefix, path, throwable);
                if (ctx.channel().isActive()) {
                    // Map failure class to grpc-status (retryable/deadline semantics)
                    sendTrailers(ctx, WireStatus.fromThrowable(throwable),
                            "Stream failed: " + throwable.getMessage());
                }
            }

            @Override
            public void onComplete() {
                if (ctx.channel().isActive()) {
                    sendTrailers(ctx, WireConstants.STATUS_OK, null);
                }
            }
        });
    }

    /** Send the initial response HEADERS with :status 200 and content-type. */
    protected void sendResponseHeaders(ChannelHandlerContext ctx) {
        if (responseHeadersSent) {
            return;
        }
        responseHeadersSent = true;
        Http2Headers headers = new DefaultHttp2Headers()
                .status("200")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC);
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

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
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
            sendTrailers(ctx, WireConstants.STATUS_INTERNAL, cause.getMessage());
        }
        ctx.close();
    }
}
