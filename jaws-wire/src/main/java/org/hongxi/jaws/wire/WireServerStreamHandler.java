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

/**
 * Per-stream inbound handler for the gRPC server ({@link WireServer}).
 * <p>
 * One instance is created per HTTP/2 stream by {@code Http2MultiplexHandler}.
 * It performs the following:
 * <ol>
 *   <li>Parses the {@code :path} pseudo-header to resolve the {@link WireMethodHandler}</li>
 *   <li>Accumulates DATA frame bytes and extracts gRPC frames via {@link WireFrameCodec}</li>
 *   <li>Decodes the protobuf request message and dispatches to the handler on the business executor</li>
 *   <li>Encodes the protobuf response as a gRPC frame, writes DATA + Trailers (END_STREAM)</li>
 * </ol>
 * <p>
 * All business logic runs off the event loop thread.
 *
 * @author shenhongxi
 */
class WireServerStreamHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireServerStreamHandler.class);

    private final WireServiceRegistry registry;
    private final ExecutorService executor;

    private WireMethodHandler handler;
    private String path;
    private ByteBuf accumulator;
    private boolean dispatched;
    private boolean responseHeadersSent;

    WireServerStreamHandler(WireServiceRegistry registry, ExecutorService executor) {
        this.registry = registry;
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
            log.error("Wire server stream error: path={}", path, e);
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

        handler = registry.resolve(path);
        if (handler == null) {
            sendTrailers(ctx, WireConstants.STATUS_NOT_FOUND, "Method not found: " + path);
            return;
        }

        if (endStream) {
            sendTrailers(ctx, WireConstants.STATUS_INTERNAL, "Missing request payload");
        }
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            if (handler == null) {
                return; // headers not yet processed or method not found
            }
            if (dispatched) {
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
     * Hand the complete accumulated payload to the business executor.
     */
    private void dispatch(ChannelHandlerContext ctx) {
        if (dispatched) {
            return;
        }
        dispatched = true;

        final WireMethodHandler methodHandler = this.handler;
        final ByteBuf frameData = this.accumulator;

        executor.execute(() -> {
            ByteBuf frame = null;
            try {
                frame = WireFrameCodec.tryExtractFrame(frameData);
                if (frame == null) {
                    sendTrailers(ctx, WireConstants.STATUS_INTERNAL, "Incomplete gRPC frame");
                    return;
                }
                Message request = WireFrameCodec.decode(frame, methodHandler.getRequestParser());
                Message response = methodHandler.handle(request);

                if (ctx.channel().isActive()) {
                    sendResponseHeaders(ctx);
                    ByteBuf responseFrame = WireFrameCodec.encode(response, ctx.alloc());
                    // Send response DATA (not end stream)
                    ctx.write(new DefaultHttp2DataFrame(responseFrame, false));
                    // Send trailers with END_STREAM
                    sendTrailers(ctx, WireConstants.STATUS_OK, null);
                }
            } catch (Exception e) {
                log.error("Wire server invoke failed: path={}", path, e);
                if (ctx.channel().isActive()) {
                    sendTrailers(ctx, WireConstants.STATUS_INTERNAL,
                            "Invoke failed: " + e.getMessage());
                }
            } finally {
                if (frame != null) {
                    frame.release();
                }
                if (frameData != null) {
                    frameData.release();
                }
            }
        });
    }

    /** Send the initial response HEADERS with :status 200 and content-type. */
    private void sendResponseHeaders(ChannelHandlerContext ctx) {
        if (responseHeadersSent) {
            return;
        }
        responseHeadersSent = true;
        Http2Headers headers = new DefaultHttp2Headers()
                .status("200")
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC);
        ctx.write(new DefaultHttp2HeadersFrame(headers, false));
    }

    private void sendTrailers(ChannelHandlerContext ctx, int status, String message) {
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
        log.error("Wire server stream exception: path={}", path, cause);
        if (!dispatched) {
            sendTrailers(ctx, WireConstants.STATUS_INTERNAL, cause.getMessage());
        }
        ctx.close();
    }
}
