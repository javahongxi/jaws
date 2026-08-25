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
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
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
 *
 * @author shenhongxi
 */
class WireSpiServerStreamHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireSpiServerStreamHandler.class);

    private final MessageHandler messageHandler;
    private final Channel serverChannel;
    private final ExecutorService executor;

    private String path;
    private String serviceName;
    private String methodName;
    private ByteBuf accumulator;
    private boolean dispatched;
    private boolean responseHeadersSent;

    WireSpiServerStreamHandler(MessageHandler messageHandler, Channel serverChannel,
                               ExecutorService executor) {
        this.messageHandler = messageHandler;
        this.serverChannel = serverChannel;
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
            log.error("Wire SPI stream error: path={}", path, e);
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

        // Parse gRPC path: /{serviceName}/{methodName}
        parsePath(path);

        if (endStream) {
            sendTrailers(ctx, WireConstants.STATUS_INTERNAL, "Missing request payload");
        }
    }

    /**
     * Parse the gRPC path into service name and method name.
     * Path format: {@code /{package.ServiceName}/{MethodName}}
     */
    private void parsePath(String path) {
        if (path == null || !path.startsWith("/")) {
            return;
        }
        String trimmed = path.substring(1);
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx > 0) {
            serviceName = trimmed.substring(0, slashIdx);
            methodName = trimmed.substring(slashIdx + 1);
        }
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
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

    private void dispatch(ChannelHandlerContext ctx) {
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
                Object result = future.join();

                // The result is typically a Response wrapping the business return value.
                // For streaming methods, the wrapped value is a Flow.Publisher.
                Object value = result;
                if (result instanceof org.hongxi.jaws.rpc.Response response) {
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

    /**
     * Subscribe to the streaming publisher and write each emitted protobuf
     * {@link Message} as a gRPC DATA frame. On completion, send trailers
     * with {@code grpc-status: OK}.
     */
    @SuppressWarnings("unchecked")
    private void dispatchStreaming(ChannelHandlerContext ctx, Flow.Publisher<?> publisher) {
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
                    log.warn("Wire SPI streaming: expected protobuf Message but got: {}",
                            item != null ? item.getClass().getName() : "null");
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Wire SPI streaming error: path={}", path, throwable);
                if (ctx.channel().isActive()) {
                    sendTrailers(ctx, WireConstants.STATUS_INTERNAL,
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
        if (accumulator != null && !dispatched) {
            accumulator.release();
            accumulator = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Wire SPI stream exception: path={}", path, cause);
        if (!dispatched) {
            sendTrailers(ctx, WireConstants.STATUS_INTERNAL, cause.getMessage());
        }
        ctx.close();
    }
}
