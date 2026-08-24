package org.hongxi.jaws.transport.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-stream inbound handler for the HTTP/2 server.
 * <p>
 * One instance is created per HTTP/2 stream by {@code Http2MultiplexHandler}.
 * It reassembles the request DATA frames (bounded by {@code maxContentLength}),
 * resolves the serialization from the {@code x-jaws-serialization} header,
 * decodes the Jaws {@link DefaultRequest}, dispatches it to the
 * {@link MessageHandler} pipeline on the business executor, and writes the
 * serialized response back as HEADERS + DATA(END_STREAM) on the same stream.
 * <p>
 * All processing beyond frame accumulation happens off the event loop, so
 * transport threads are never blocked by business logic.
 *
 * @author shenhongxi
 */
class Http2ServerStreamHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Http2ServerStreamHandler.class);

    private final MessageHandler messageHandler;
    private final Channel serverChannel;
    private final ExecutorService executor;
    private final AtomicInteger activeRequests;
    private final String defaultSerializationName;
    private final int maxContentLength;

    private Serialization serialization;
    private ByteArrayOutputStream buffer;
    private boolean overLimit;
    private boolean dispatched;

    Http2ServerStreamHandler(MessageHandler messageHandler, Channel serverChannel,
                             ExecutorService executor, AtomicInteger activeRequests,
                             String defaultSerializationName, int maxContentLength) {
        this.messageHandler = messageHandler;
        this.serverChannel = serverChannel;
        this.executor = executor;
        this.activeRequests = activeRequests;
        this.defaultSerializationName = defaultSerializationName;
        this.maxContentLength = maxContentLength;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http2HeadersFrame headersFrame) {
            onHeaders(ctx, headersFrame.headers(), headersFrame.isEndStream());
        } else if (msg instanceof Http2DataFrame dataFrame) {
            onData(ctx, dataFrame);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    private void onHeaders(ChannelHandlerContext ctx, Http2Headers headers, boolean endStream) {
        String name = headers.get(Http2Constants.HEADER_SERIALIZATION) != null
                ? headers.get(Http2Constants.HEADER_SERIALIZATION).toString()
                : defaultSerializationName;
        try {
            serialization = Http2PayloadCodec.resolveSerialization(name);
        } catch (Exception e) {
            log.error("unsupported serialization: {}", name, e);
            sendError(ctx, Http2Constants.STATUS_BAD_REQUEST, "Unsupported serialization: " + name);
            return;
        }

        if (endStream) {
            // HEADERS-only request carries no payload
            sendError(ctx, Http2Constants.STATUS_BAD_REQUEST, "Empty request payload");
        }
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            if (serialization == null) {
                sendError(ctx, Http2Constants.STATUS_BAD_REQUEST, "DATA frame before HEADERS");
                return;
            }
            if (dispatched || overLimit) {
                // payload already completed or rejected; discard trailing frames
                return;
            }

            ByteBuf content = dataFrame.content();
            if (buffer == null) {
                buffer = new ByteArrayOutputStream(Math.min(content.readableBytes(), maxContentLength));
            }
            if (buffer.size() + content.readableBytes() > maxContentLength) {
                overLimit = true;
                sendError(ctx, Http2Constants.STATUS_BAD_REQUEST,
                        "Request payload exceeds maxContentLength: " + maxContentLength);
                return;
            }
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            buffer.write(bytes, 0, bytes.length);

            if (dataFrame.isEndStream() && !overLimit) {
                dispatch(ctx, buffer.toByteArray());
            }
        } finally {
            dataFrame.release();
        }
    }

    /**
     * Hand the complete payload to the business executor; the event loop
     * returns immediately to serve other streams.
     */
    private void dispatch(ChannelHandlerContext ctx, byte[] payload) {
        if (dispatched) {
            return;
        }
        dispatched = true;
        activeRequests.incrementAndGet();

        long startTime = System.currentTimeMillis();
        executor.execute(() -> {
            final DefaultRequest request;
            try {
                request = Http2PayloadCodec.decodeRequest(payload, serialization);
            } catch (Exception e) {
                log.error("HTTP/2 request deserialization failed", e);
                sendError(ctx, Http2Constants.STATUS_BAD_REQUEST,
                        "Request deserialization failed: " + e.getMessage());
                activeRequests.decrementAndGet();
                return;
            }

            try {
                RpcContext.init(request);

                CompletableFuture<Object> future = messageHandler.handleAsync(serverChannel, request);
                future.whenComplete((result, throwable) -> {
                    try {
                        RpcContext.init(request);
                        DefaultResponse response;
                        if (throwable != null) {
                            log.error("HTTP/2 invoke failed: {}", request, throwable);
                            response = new DefaultResponse(request.getRequestId());
                            response.setException(new RuntimeException(
                                    "process request failed: " + throwable.getMessage(), throwable));
                        } else if (result instanceof DefaultResponse dr) {
                            response = dr;
                        } else if (result instanceof Response r) {
                            response = new DefaultResponse(r);
                        } else {
                            response = new DefaultResponse(result);
                        }
                        response.setRequestId(request.getRequestId());
                        response.setProcessTime(System.currentTimeMillis() - startTime);

                        if (ctx.channel().isActive()) {
                            byte[] responseBytes = Http2PayloadCodec.encodeResponse(response, serialization);
                            Http2Headers respHeaders = new DefaultHttp2Headers()
                                    .status(Http2Constants.STATUS_OK)
                                    .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.CONTENT_TYPE);
                            ctx.write(new DefaultHttp2HeadersFrame(respHeaders));
                            ctx.writeAndFlush(new DefaultHttp2DataFrame(
                                    Unpooled.wrappedBuffer(responseBytes), true));
                        }
                    } catch (Exception e) {
                        log.error("HTTP/2 response serialization failed: requestId={}",
                                request.getRequestId(), e);
                        sendError(ctx, Http2Constants.STATUS_INTERNAL_ERROR,
                                "Response serialization failed: " + e.getMessage());
                    } finally {
                        RpcContext.destroy();
                        activeRequests.decrementAndGet();
                    }
                });
            } catch (Exception e) {
                log.error("HTTP/2 invoke failed: {}", request, e);
                sendError(ctx, Http2Constants.STATUS_INTERNAL_ERROR,
                        "Process request failed: " + e.getMessage());
                RpcContext.destroy();
                activeRequests.decrementAndGet();
            }
        });
    }

    private void sendError(ChannelHandlerContext ctx, String status, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        Http2Headers headers = new DefaultHttp2Headers()
                .status(status)
                .set(Http2Constants.HEADER_CONTENT_TYPE, "text/plain; charset=utf-8");
        ctx.write(new DefaultHttp2HeadersFrame(headers));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(
                Unpooled.copiedBuffer(message, StandardCharsets.UTF_8), true));
    }
}
