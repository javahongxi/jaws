package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;

/**
 * Per-stream inbound handler for the gRPC server in direct API mode.
 * <p>
 * One instance is created per HTTP/2 stream by {@code Http2MultiplexHandler}.
 * It resolves the {@code :path} against the {@link WireServiceRegistry},
 * decodes the protobuf request, dispatches to the {@link WireMethodHandler}
 * on the business executor (streaming first, unary fallback), and encodes
 * the protobuf response as a gRPC frame.
 * <p>
 * The gRPC wire mechanics (DATA accumulation, frame extraction, response
 * writing, streaming dispatch, lifecycle) are provided by
 * {@link AbstractWireStreamHandler}.
 *
 * @author shenhongxi
 */
class WireServerStreamHandler extends AbstractWireStreamHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WireServerStreamHandler.class);

    private final WireServiceRegistry registry;

    private WireMethodHandler handler;

    WireServerStreamHandler(WireServiceRegistry registry, ExecutorService executor) {
        super("Wire server", executor);
        this.registry = registry;
    }

    @Override
    protected void onHeadersResolved(ChannelHandlerContext ctx, String path, boolean endStream) {
        handler = registry.resolve(path);
        if (handler == null) {
            sendTrailers(ctx, WireConstants.STATUS_NOT_FOUND, "Method not found: " + path);
        }
    }

    @Override
    protected boolean acceptsData() {
        return handler != null; // stop buffering once the path failed to resolve
    }

    /**
     * Hand the complete accumulated payload to the business executor.
     */
    @Override
    protected void dispatch(ChannelHandlerContext ctx) {
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

                try {
                    // Try streaming first; falls back to UnsupportedOperationException
                    Flow.Publisher<Message> publisher = methodHandler.handleStream(request);
                    dispatchStreaming(ctx, publisher);
                } catch (UnsupportedOperationException e) {
                    // Unary fallback
                    Message response = methodHandler.handle(request);
                    if (ctx.channel().isActive()) {
                        sendResponseHeaders(ctx);
                        ByteBuf responseFrame = WireFrameCodec.encode(response, ctx.alloc());
                        ctx.write(new DefaultHttp2DataFrame(responseFrame, false));
                        sendTrailers(ctx, WireConstants.STATUS_OK, null);
                    }
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
}
