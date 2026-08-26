package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;

/**
 * SPI-mode per-stream handler for the gRPC server.
 * <p>
 * Unlike {@link WireServerStreamHandler} which uses a {@link WireServiceRegistry}
 * and protobuf-typed {@link WireMethodHandler}, this handler bridges directly
 * to the Jaws {@link MessageHandler} pipeline. It extracts the raw protobuf
 * bytes from the gRPC frame and passes them as the request argument, then
 * encodes the protobuf {@link Message} response back as a gRPC frame.
 * <p>
 * The gRPC path ({@code /{service}/{method}}) is parsed to populate
 * {@link DefaultRequest#getInterfaceName()} and {@link DefaultRequest#getMethodName()},
 * enabling the Jaws framework to route the call to the correct provider.
 * <p>
 * The gRPC wire mechanics (DATA accumulation, frame extraction, response
 * writing, streaming dispatch, lifecycle) are provided by
 * {@link AbstractWireStreamHandler}.
 *
 * @author shenhongxi
 */
class WireSpiServerStreamHandler extends AbstractWireStreamHandler {
    private static final Logger log = LoggerFactory.getLogger(WireSpiServerStreamHandler.class);

    private final MessageHandler messageHandler;
    private final Channel serverChannel;

    private String serviceName;
    private String methodName;

    /** Caller's deadline parsed from the grpc-timeout header, in ms; 0 = none. */
    private long grpcTimeoutMs;

    WireSpiServerStreamHandler(MessageHandler messageHandler, Channel serverChannel,
                               ExecutorService executor) {
        super("Wire SPI", executor);
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

        // Honor the caller's deadline (gRPC timeout propagation): grpc-timeout header
        CharSequence timeoutSeq = headers.get(WireStatus.GRPC_TIMEOUT);
        if (timeoutSeq != null) {
            grpcTimeoutMs = WireStatus.decodeTimeout(timeoutSeq.toString());
            if (grpcTimeoutMs == -1) {
                log.warn("Wire SPI malformed grpc-timeout header: {}", timeoutSeq);
                grpcTimeoutMs = 0;
            }
        }
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

        executor.execute(() -> {
            ByteBuf frame = null;
            try {
                frame = WireFrameCodec.tryExtractFrame(frameData);
                if (frame == null) {
                    sendTrailers(ctx, WireConstants.STATUS_INTERNAL, "Incomplete gRPC frame");
                    return;
                }

                // Extract raw protobuf bytes (skip 5-byte gRPC header)
                frame.skipBytes(1); // compressed flag
                int length = frame.readInt();
                byte[] protobufBytes = new byte[length];
                frame.readBytes(protobufBytes);

                // Build Jaws request with raw protobuf bytes as argument
                DefaultRequest jawsRequest = new DefaultRequest();
                jawsRequest.setInterfaceName(svcName);
                jawsRequest.setMethodName(mName);
                jawsRequest.setArguments(new Object[]{protobufBytes});

                CompletableFuture<Object> future = messageHandler.handleAsync(serverChannel, jawsRequest);
                if (grpcTimeoutMs > 0) {
                    // Honor the caller's deadline: on expiry fail the stream with the
                    // jaws timeout error code, which maps to grpc-status DEADLINE_EXCEEDED
                    future = future.orTimeout(grpcTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                Object result = future.join();

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
                    if (ctx.channel().isActive()) {
                        sendResponseHeaders(ctx);
                        ByteBuf responseFrame = WireFrameCodec.encode(responseMessage, ctx.alloc());
                        ctx.write(new DefaultHttp2DataFrame(responseFrame, false));
                        sendTrailers(ctx, WireConstants.STATUS_OK, null);
                    }
                }
            } catch (Exception e) {
                log.error("Wire SPI invoke failed: path={}", path, e);
                if (ctx.channel().isActive()) {
                    // Map the failure class to grpc-status so standard gRPC clients
                    // see retryable (UNAVAILABLE) / deadline (DEADLINE_EXCEEDED)
                    // semantics instead of a blanket INTERNAL
                    sendTrailers(ctx, WireStatus.fromThrowable(e),
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
