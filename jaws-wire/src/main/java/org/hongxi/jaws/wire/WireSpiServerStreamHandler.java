package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * SPI-mode per-stream handler for the gRPC server.
 * <p>
 * Unlike {@link WireServerStreamHandler} which uses a {@link WireServiceRegistry}
 * and protobuf-typed {@link WireMethodHandler}, this handler bridges directly
 * to the Jaws {@link MessageHandler} pipeline. It extracts the raw protobuf
 * bytes from the gRPC frame (decompressing per {@code grpc-encoding}) and
 * passes them as the request argument, then encodes the protobuf
 * {@link Message} response back as a gRPC frame.
 * <p>
 * The gRPC path ({@code /{service}/{method}}) is parsed to populate
 * {@link DefaultRequest#getInterfaceName()} and {@link DefaultRequest#getMethodName()},
 * enabling the Jaws framework to route the call to the correct provider.
 * Inbound custom metadata (non-reserved headers) is carried into the Jaws
 * request as attachments so the filter chain can consume it.
 * <p>
 * The gRPC wire mechanics (DATA accumulation, frame extraction, response
 * writing, deadline/cancellation handling, streaming dispatch, lifecycle)
 * are provided by {@link AbstractWireStreamHandler}.
 *
 * @author shenhongxi
 */
class WireSpiServerStreamHandler extends AbstractWireStreamHandler {
    private static final Logger log = LoggerFactory.getLogger(WireSpiServerStreamHandler.class);

    private final MessageHandler messageHandler;
    private final Channel serverChannel;

    private String serviceName;
    private String methodName;

    WireSpiServerStreamHandler(MessageHandler messageHandler, Channel serverChannel,
                               ExecutorService executor,
                               int maxMessageSize, String responseEncoding) {
        super("Wire SPI", executor, maxMessageSize, responseEncoding);
        this.messageHandler = messageHandler;
        this.serverChannel = serverChannel;
    }

    @Override
    protected void onHeadersResolved(ChannelHandlerContext ctx, Http2Headers headers, String path, boolean endStream) {
        // Parse gRPC path: /{serviceName}/{methodName}
        if (path.startsWith("/")) {
            String trimmed = path.substring(1);
            int slashIdx = trimmed.indexOf('/');
            if (slashIdx > 0) {
                serviceName = trimmed.substring(0, slashIdx);
                methodName = trimmed.substring(slashIdx + 1);
            }
        }
        // grpc-timeout / grpc-encoding / metadata are parsed by the base class
    }

    @Override
    protected void dispatch(ChannelHandlerContext ctx) {
        if (dispatched) {
            return;
        }
        dispatched = true;

        final ByteBuf frameData = this.accumulator;
        final String svcName = this.serviceName;
        final String mName = this.methodName;
        final Map<String, String> callAttachments = this.attachments;

        executor.execute(() -> {
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

                // Extract raw protobuf bytes, decompressing when the frame is compressed
                byte[] protobufBytes;
                try {
                    protobufBytes = WireFrameCodec.extractPayload(frame, requestEncoding);
                } catch (IllegalArgumentException e) {
                    sendError(ctx, WireConstants.STATUS_UNIMPLEMENTED, e.getMessage());
                    return;
                }

                // Build Jaws request with raw protobuf bytes as argument
                DefaultRequest jawsRequest = new DefaultRequest();
                jawsRequest.setInterfaceName(svcName);
                jawsRequest.setMethodName(mName);
                jawsRequest.setArguments(new Object[]{protobufBytes});
                for (Map.Entry<String, String> entry : callAttachments.entrySet()) {
                    jawsRequest.setAttachment(entry.getKey(), entry.getValue());
                }

                // Surface the request attachments (gRPC metadata) to the Jaws
                // pipeline via RpcContext, consistent with the netty/http2 transports
                RpcContext.init(jawsRequest);

                CompletableFuture<Object> future = messageHandler.handleAsync(serverChannel, jawsRequest);
                if (deadlineMs > 0) {
                    // Honor the caller's deadline: on expiry fail the stream with the
                    // jaws timeout error code, which maps to grpc-status DEADLINE_EXCEEDED
                    future = future.orTimeout(remainingDeadlineMs(), TimeUnit.MILLISECONDS);
                }
                Object result = future.join();

                if (cancelled || !ctx.channel().isActive()) {
                    return;
                }

                // The result is typically a Response wrapping the business return value.
                // For streaming methods, the wrapped value is a Flow.Publisher.
                Object value = result;
                if (result instanceof Response response) {
                    if (response.getException() != null) {
                        throw new RuntimeException("Provider error", response.getException());
                    }
                    value = response.getRawValue();
                }

                if (value instanceof Flow.Publisher<?> publisher) {
                    // Server streaming: subscribe and emit each Message as a DATA frame
                    dispatchStreaming(ctx, publisher);
                } else {
                    // Unary: single response Message
                    Message responseMessage = extractMessage(result);
                    if (isDeadlineExceeded()) {
                        // The deadline passed while the handler was running; do
                        // not send the result, report DEADLINE_EXCEEDED instead
                        sendError(ctx, WireStatus.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
                        return;
                    }
                    sendResponseHeaders(ctx);
                    ByteBuf responseFrame = WireFrameCodec.encode(responseMessage, ctx.alloc(), responseEncoding);
                    ctx.write(new DefaultHttp2DataFrame(responseFrame, false));
                    sendTrailers(ctx, WireConstants.STATUS_OK, null);
                }
            } catch (Exception e) {
                log.error("Wire SPI invoke failed: path={}", path, e);
                if (!cancelled && ctx.channel().isActive()) {
                    // Map the failure class to grpc-status so standard gRPC clients
                    // see retryable (UNAVAILABLE) / deadline (DEADLINE_EXCEEDED)
                    // semantics instead of a blanket INTERNAL
                    sendError(ctx, WireStatus.fromThrowable(e),
                            "Invoke failed: " + e.getMessage());
                }
            } finally {
                RpcContext.destroy();
                if (frame != null) {
                    frame.release();
                }
                if (frameData != null) {
                    frameData.release();
                }
            }
        });
    }

    private Message extractMessage(Object result) {
        if (result instanceof DefaultResponse dr) {
            Object value = dr.getRawValue();
            if (value instanceof Message msg) {
                return msg;
            }
            throw new RuntimeException("Wire SPI expected protobuf Message response but got: "
                    + (value != null ? value.getClass().getName() : "null"));
        } else if (result instanceof Message msg) {
            return msg;
        }
        throw new RuntimeException("Wire SPI unexpected result type: "
                + (result != null ? result.getClass().getName() : "null"));
    }
}
