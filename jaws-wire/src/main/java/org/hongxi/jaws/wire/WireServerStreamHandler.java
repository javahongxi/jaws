package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;

/**
 * Per-stream inbound handler for the gRPC server in direct API mode.
 * <p>
 * One instance is created per HTTP/2 stream by {@code Http2MultiplexHandler}.
 * It resolves the {@code :path} against the {@link WireServiceRegistry},
 * decodes the protobuf request (decompressing per {@code grpc-encoding}),
 * dispatches to the {@link WireMethodHandler} on the business executor
 * (streaming first, unary fallback) with the inbound metadata exposed via
 * {@link WireCallContext}, and encodes the protobuf response as a gRPC frame.
 * <p>
 * The gRPC wire mechanics (DATA accumulation, frame extraction, response
 * writing, deadline/cancellation handling, streaming dispatch, lifecycle)
 * are provided by {@link AbstractWireStreamHandler}.
 *
 * @author shenhongxi
 */
class WireServerStreamHandler extends AbstractWireStreamHandler {
    private static final Logger log = LoggerFactory.getLogger(WireServerStreamHandler.class);

    private final WireServiceRegistry registry;

    private WireMethodHandler handler;

    WireServerStreamHandler(WireServiceRegistry registry, ExecutorService serverExecutor,
                            int maxMessageSize, String responseEncoding) {
        super("Wire server", serverExecutor, maxMessageSize, responseEncoding);
        this.registry = registry;
    }

    @Override
    protected void onHeadersResolved(ChannelHandlerContext ctx, Http2Headers headers, String path, boolean endStream) {
        handler = registry.resolve(path);
        if (handler == null) {
            sendError(ctx, WireConstants.STATUS_NOT_FOUND, "Method not found: " + path);
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
        final WireCallContext callContext = attachments.isEmpty()
                ? WireCallContext.EMPTY : new WireCallContext(attachments);

        try {
            serverExecutor.execute(() -> {
                ByteBuf frame = null;
                try {
                    // Honor the caller's deadline before doing any work
                    if (isDeadlineExceeded()) {
                        sendError(ctx, WireStatus.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
                        return;
                    }

                    frame = WireFrameCodec.tryExtractFrame(frameData);
                    if (frame == null) {
                        sendError(ctx, WireConstants.STATUS_INTERNAL, "Incomplete gRPC frame");
                        return;
                    }
                    Message request;
                    try {
                        request = WireFrameCodec.decode(frame, methodHandler.getRequestParser(), requestEncoding);
                    } catch (IllegalArgumentException e) {
                        sendError(ctx, WireConstants.STATUS_UNIMPLEMENTED, e.getMessage());
                        return;
                    }

                    if (methodHandler.methodType() == WireMethodHandler.MethodType.SERVER_STREAMING) {
                        Flow.Publisher<Message> publisher = methodHandler.handleStream(request, callContext);
                        dispatchStream(ctx, publisher);
                    } else {
                        Message response = methodHandler.handle(request, callContext);
                        if (canceled || !ctx.channel().isActive()) {
                            return;
                        }
                        if (isDeadlineExceeded()) {
                            // The deadline passed while the handler was running; do
                            // not send the result, report DEADLINE_EXCEEDED instead
                            sendError(ctx, WireStatus.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
                            return;
                        }
                        sendResponseHeaders(ctx);
                        ByteBuf responseFrame = WireFrameCodec.encode(response, ctx.alloc(), responseEncoding);
                        ctx.write(new DefaultHttp2DataFrame(responseFrame, false));
                        sendTrailers(ctx, WireConstants.STATUS_OK, null);
                    }
                } catch (Exception e) {
                    log.error("Wire server invoke failed: path={}", path, e);
                    if (!canceled && ctx.channel().isActive()) {
                        // Map failure class to grpc-status (retryable/deadline semantics)
                        sendError(ctx, WireStatus.fromThrowable(e),
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
        } catch (RejectedExecutionException e) {
            // Business thread pool is full; fail fast with UNAVAILABLE so the
            // caller can retry on another provider
            rejectCall(ctx, e);
        }
    }
}
