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
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
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
 * Supports both unary and server-streaming invocations. For server streaming,
 * the {@code x-jaws-streaming} header value is {@code "server"} and the
 * provider method returns a {@link Flow.Publisher} whose items are each
 * written as a separate DATA frame.
 * <p>
 * All processing beyond frame accumulation happens off the event loop, so
 * transport threads are never blocked by business logic.
 * <p>
 * Gateway-friendly enhancements:
 * <ul>
 *   <li>Built-in {@code GET /health} endpoint returning 200 OK without dispatching</li>
 *   <li>Reads mirrored metadata headers ({@code x-jaws-interface}, {@code x-jaws-method},
 *       etc.) for gateway-level routing and observability</li>
 * </ul>
 *
 * @author shenhongxi
 */
class Http2ServerStreamHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Http2ServerStreamHandler.class);

    private final MessageHandler messageHandler;
    private final ExecutorService executor;
    private final String defaultSerializationName;
    private final AtomicInteger activeRequests;
    private final int maxContentLength;

    private Serialization serialization;
    private StreamType streamType = StreamType.UNARY;
    private ByteArrayOutputStream buffer;
    private boolean overLimit;
    private boolean dispatched;

    Http2ServerStreamHandler(MessageHandler messageHandler,
                             ExecutorService executor,
                             String defaultSerializationName,
                             AtomicInteger activeRequests,
                             int maxContentLength) {
        this.messageHandler = messageHandler;
        this.executor = executor;
        this.defaultSerializationName = defaultSerializationName;
        this.activeRequests = activeRequests;
        this.maxContentLength = maxContentLength;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http2HeadersFrame headersFrame) {
            onHeaders(ctx, headersFrame);
        } else if (msg instanceof Http2DataFrame dataFrame) {
            onData(ctx, dataFrame);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    private void onHeaders(ChannelHandlerContext ctx, Http2HeadersFrame headersFrame) {
        Http2Headers headers = headersFrame.headers();
        // Extract HTTP method and path for health check routing
        // Parsed from HEADERS frame for metadata mirror / health check
        String method = Objects.toString(headers.method(), null);
        String path = Objects.toString(headers.path(), null);

        // Health check: GET /health returns immediately without dispatching
        if ("GET".equals(method) && Http2Constants.HEALTH_PATH.equals(path)) {
            sendHealthResponse(ctx);
            return;
        }

        String serializationName = Objects.toString(
                headers.get(Http2Constants.HEADER_SERIALIZATION), defaultSerializationName);
        serialization = Http2PayloadCodec.resolveSerialization(serializationName);
        if (serialization == null) {
            sendError(ctx, Http2Constants.STATUS_BAD_REQUEST, "Unsupported serialization: " + serializationName);
            return;
        }

        // Extract streaming mode
        String streamingHeader = Objects.toString(
                headers.get(Http2Constants.HEADER_STREAMING), null);
        streamType = StreamType.fromValue(streamingHeader);

        if (headersFrame.isEndStream() && streamType == StreamType.UNARY) {
            // HEADERS-only request carries no payload, which a unary call requires
            sendError(ctx, Http2Constants.STATUS_BAD_REQUEST, "Empty request payload");
        }
    }

    /**
     * Respond to {@code GET /health} with 200 OK + "OK" body.
     * No dispatch to business thread pool, no serialization.
     */
    private void sendHealthResponse(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) {
            return;
        }
        byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
        Http2Headers respHeaders = new DefaultHttp2Headers()
                .status(Http2Constants.STATUS_OK)
                .set(Http2Constants.HEADER_CONTENT_TYPE, "text/plain; charset=utf-8");
        ctx.write(new DefaultHttp2HeadersFrame(respHeaders));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), true));
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            if (serialization == null) {
                sendError(ctx, Http2Constants.STATUS_BAD_REQUEST, "DATA frame before HEADERS");
                return;
            }
            if (overLimit) {
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

            if (dataFrame.isEndStream()) {
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
                log.error("Failed to decode HTTP/2 request", e);
                sendError(ctx, Http2Constants.STATUS_BAD_REQUEST,
                        "Failed to decode request: " + e.getMessage());
                activeRequests.decrementAndGet();
                return;
            }

            try {
                RpcContext.init(request);

                if (streamType == StreamType.SERVER) {
                    dispatchStream(ctx, request);
                } else {
                    dispatchUnary(ctx, request, startTime);
                }
            } catch (Exception e) {
                log.error("HTTP/2 invoke failed: {}", request, e);
                sendError(ctx, Http2Constants.STATUS_INTERNAL_ERROR,
                        "Process request failed: " + e.getMessage());
                RpcContext.destroy();
                activeRequests.decrementAndGet();
            }
        });
    }

    /**
     * Dispatch a unary (request-response) invocation.
     */
    private void dispatchUnary(ChannelHandlerContext ctx, Request request, long startTime) {
        CompletableFuture<Object> future = messageHandler.handleAsync(request);
        future.whenComplete((result, throwable) -> {
            try {
                RpcContext.init(request);
                DefaultResponse response;
                if (throwable != null) {
                    log.error("HTTP/2 invoke failed: {}", request, throwable);
                    response = new DefaultResponse();
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
                            Unpooled.wrappedBuffer(responseBytes), true))
                            .addListener(f -> {
                                if (!f.isSuccess()) {
                                    log.error("Failed to write unary response: requestId={}",
                                            request.getRequestId(), f.cause());
                                }
                            });
                }
            } catch (Exception e) {
                log.error("Failed to encode HTTP/2 response: requestId={}",
                        request.getRequestId(), e);
                sendError(ctx, Http2Constants.STATUS_INTERNAL_ERROR,
                        "Failed to encode response: " + e.getMessage());
            } finally {
                RpcContext.destroy();
                activeRequests.decrementAndGet();
            }
        });
    }

    /**
     * Dispatch a streaming invocation: single request, server streams
     * multiple response items via {@link Flow.Publisher}.
     */
    private void dispatchStream(ChannelHandlerContext ctx, Request request) {
        Flow.Publisher<Object> publisher = messageHandler.handleStream(request);

        // Send response headers first (without END_STREAM)
        if (ctx.channel().isActive()) {
            Http2Headers respHeaders = new DefaultHttp2Headers()
                    .status(Http2Constants.STATUS_OK)
                    .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.CONTENT_TYPE)
                    .set(Http2Constants.HEADER_STREAMING, StreamType.SERVER.getValue());
            ctx.write(new DefaultHttp2HeadersFrame(respHeaders));
        }

        // Subscribe to the publisher and stream responses.
        // Request all items at once to avoid synchronous recursion that would
        // occur if we called subscription.request(1) from within onNext().
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Object item) {
                if (!ctx.channel().isActive()) {
                    subscription.cancel();
                    return;
                }
                try {
                    byte[] itemBytes = Http2StreamCodec.encodeItem(item, serialization);
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(
                            Unpooled.wrappedBuffer(itemBytes), false));
                } catch (Exception e) {
                    log.error("Failed to encode stream item", e);
                    subscription.cancel();
                    sendStreamError(ctx, e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Server streaming error: requestId={}", request.getRequestId(), throwable);
                sendStreamError(ctx, throwable);
                finishStream();
            }

            @Override
            public void onComplete() {
                // Send final empty DATA frame with END_STREAM
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttp2DataFrame(true));
                }
                finishStream();
            }

            private void finishStream() {
                RpcContext.destroy();
                activeRequests.decrementAndGet();
            }
        });
    }

    private void sendStreamError(ChannelHandlerContext ctx, Throwable error) {
        if (ctx.channel().isActive()) {
            try {
                String errorMsg = Objects.toString(error.getMessage(), error.getClass().getName());
                byte[] errorBytes = errorMsg.getBytes(StandardCharsets.UTF_8);
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                        Unpooled.wrappedBuffer(errorBytes), true))
                        .addListener(f -> {
                            if (!f.isSuccess()) {
                                log.error("Failed to send stream error", f.cause());
                            }
                        });
            } catch (Exception e) {
                log.error("Failed to send stream error", e);
            }
        }
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
                Unpooled.copiedBuffer(message, StandardCharsets.UTF_8), true))
                .addListener(f -> {
                    if (!f.isSuccess()) {
                        log.error("Failed to send error response: status={}", status, f.cause());
                    }
                });
    }
}
