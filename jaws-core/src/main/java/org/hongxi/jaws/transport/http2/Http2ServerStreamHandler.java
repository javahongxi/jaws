package org.hongxi.jaws.transport.http2;

import com.alibaba.fastjson2.JSON;
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
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
    private final Channel serverChannel;
    private final ExecutorService executor;
    private final String defaultSerializationName;
    private final AtomicInteger activeRequests;
    private final int maxContentLength;

    private Serialization serialization;
    private StreamType streamType = StreamType.UNARY;
    private ByteArrayOutputStream buffer;
    private boolean overLimit;
    private boolean dispatched;

    // gRPC compatibility state
    private boolean grpcRequest;
    private String grpcServiceName;
    private String grpcMethodName;

    Http2ServerStreamHandler(MessageHandler messageHandler,
                             Channel serverChannel,
                             ExecutorService executor,
                             String defaultSerializationName,
                             AtomicInteger activeRequests,
                             int maxContentLength) {
        this.messageHandler = messageHandler;
        this.serverChannel = serverChannel;
        this.executor = executor;
        this.defaultSerializationName = defaultSerializationName;
        this.activeRequests = activeRequests;
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
        // Extract HTTP method and path for health check routing
        // Parsed from HEADERS frame for metadata mirror / health check
        String method = headers.method() != null ? headers.method().toString() : null;
        String path = headers.path() != null ? headers.path().toString() : null;

        // Health check: GET /health returns immediately without dispatching
        if ("GET".equals(method) && Http2Constants.HEALTH_PATH.equals(path)) {
            sendHealthResponse(ctx);
            return;
        }

        // gRPC compatibility: detect by content-type
        String contentType = headers.get(Http2Constants.HEADER_CONTENT_TYPE) != null
                ? headers.get(Http2Constants.HEADER_CONTENT_TYPE).toString() : null;
        if (GrpcCodec.isGrpcContentType(contentType)) {
            grpcRequest = true;
            try {
                String[] parsed = GrpcCodec.parsePath(path);
                grpcServiceName = parsed[0];
                grpcMethodName = parsed[1];
            } catch (IllegalArgumentException e) {
                sendGrpcError(ctx, Http2Constants.GRPC_STATUS_INVALID_ARGUMENT, e.getMessage());
                return;
            }
            if (endStream) {
                sendGrpcError(ctx, Http2Constants.GRPC_STATUS_INVALID_ARGUMENT, "Empty request payload");
            }
            return;
        }

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

        // Extract streaming mode
        String streamingHeader = headers.get(Http2Constants.HEADER_STREAMING) != null
                ? headers.get(Http2Constants.HEADER_STREAMING).toString() : null;
        streamType = StreamType.fromWireValue(streamingHeader);

        if (endStream && streamType == StreamType.UNARY) {
            // HEADERS-only request carries no payload (only valid for unary)
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
            if (!grpcRequest && serialization == null) {
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
                if (grpcRequest) {
                    sendGrpcError(ctx, Http2Constants.GRPC_STATUS_INVALID_ARGUMENT,
                            "Request payload exceeds maxContentLength: " + maxContentLength);
                } else {
                    sendError(ctx, Http2Constants.STATUS_BAD_REQUEST,
                            "Request payload exceeds maxContentLength: " + maxContentLength);
                }
                return;
            }
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            buffer.write(bytes, 0, bytes.length);

            if (dataFrame.isEndStream()) {
                if (grpcRequest) {
                    dispatchGrpc(ctx, buffer.toByteArray());
                } else {
                    dispatch(ctx, buffer.toByteArray());
                }
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
    }

    /**
     * Dispatch a streaming invocation: single request, server streams
     * multiple response items via {@link Flow.Publisher}.
     */
    private void dispatchStream(ChannelHandlerContext ctx, Request request) {
        Flow.Publisher<Object> publisher = messageHandler.handleStream(serverChannel, request);

        // Send response headers first (without END_STREAM)
        if (ctx.channel().isActive()) {
            Http2Headers respHeaders = new DefaultHttp2Headers()
                    .status(Http2Constants.STATUS_OK)
                    .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.CONTENT_TYPE)
                    .set(Http2Constants.HEADER_STREAMING, StreamType.SERVER.getWireValue());
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
                String errorMsg = error.getMessage() != null ? error.getMessage() : error.getClass().getName();
                byte[] errorBytes = errorMsg.getBytes(StandardCharsets.UTF_8);
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                        Unpooled.wrappedBuffer(errorBytes), true));
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
                Unpooled.copiedBuffer(message, StandardCharsets.UTF_8), true));
    }

    // ==================== gRPC compatibility ====================

    /**
     * Dispatch a gRPC unary request: decode the length-prefixed frame, convert
     * the JSON body to Jaws arguments, invoke the provider, and respond with
     * gRPC framing (5-byte prefix + trailers with grpc-status).
     */
    private void dispatchGrpc(ChannelHandlerContext ctx, byte[] payload) {
        if (dispatched) {
            return;
        }
        dispatched = true;
        activeRequests.incrementAndGet();

        long startTime = System.currentTimeMillis();
        executor.execute(() -> {
            try {
                // Decode gRPC length-prefixed frame
                byte[] messageBytes = GrpcCodec.decodeFrame(payload);
                String jsonBody = new String(messageBytes, StandardCharsets.UTF_8);

                // Find provider by gRPC service name (interface name)
                Provider<?> provider = messageHandler.findProviderByInterface(grpcServiceName);
                if (provider == null) {
                    log.error("gRPC: no provider found for service={}", grpcServiceName);
                    sendGrpcError(ctx, Http2Constants.GRPC_STATUS_UNIMPLEMENTED,
                            "No provider found for service: " + grpcServiceName);
                    return;
                }

                // Build Jaws request from gRPC path
                DefaultRequest request = new DefaultRequest();
                request.setInterfaceName(grpcServiceName);
                request.setMethodName(grpcMethodName);

                // Resolve method (without paramDesc - gRPC doesn't carry it)
                Method javaMethod = provider.lookupMethod(grpcMethodName, null);
                if (javaMethod == null) {
                    log.error("gRPC: method not found: {}.{}", grpcServiceName, grpcMethodName);
                    sendGrpcError(ctx, Http2Constants.GRPC_STATUS_UNIMPLEMENTED,
                            "Method not found: " + grpcServiceName + "." + grpcMethodName);
                    return;
                }

                // Fill paramDesc from resolved method
                request.setParamDesc(ReflectUtils.getMethodParamDesc(javaMethod));

                // Convert JSON body to arguments
                Object[] args = GrpcCodec.jsonToArguments(jsonBody, javaMethod);
                request.setArguments(args);

                // Copy gRPC metadata headers as attachments
                // (timeout, custom metadata, etc.)

                RpcContext.init(request);

                // Invoke via message handler
                CompletableFuture<Object> future = messageHandler.handleAsync(serverChannel, request);
                future.whenComplete((result, throwable) -> {
                    try {
                        RpcContext.init(request);
                        DefaultResponse response;
                        if (throwable != null) {
                            log.error("gRPC invoke failed: {}.{}", grpcServiceName, grpcMethodName, throwable);
                            response = new DefaultResponse(request.getRequestId());
                            response.setException(new RuntimeException(
                                    throwable.getMessage(), throwable));
                        } else if (result instanceof DefaultResponse dr) {
                            response = dr;
                        } else if (result instanceof Response r) {
                            response = new DefaultResponse(r);
                        } else {
                            response = new DefaultResponse(result);
                        }
                        response.setRequestId(request.getRequestId());
                        response.setProcessTime(System.currentTimeMillis() - startTime);

                        sendGrpcResponse(ctx, response);
                    } catch (Exception e) {
                        log.error("gRPC response encoding failed", e);
                        sendGrpcError(ctx, Http2Constants.GRPC_STATUS_INTERNAL,
                                "Response encoding failed: " + e.getMessage());
                    } finally {
                        RpcContext.destroy();
                        activeRequests.decrementAndGet();
                    }
                });
            } catch (Exception e) {
                log.error("gRPC request processing failed", e);
                sendGrpcError(ctx, Http2Constants.GRPC_STATUS_INTERNAL,
                        "Request processing failed: " + e.getMessage());
                RpcContext.destroy();
                activeRequests.decrementAndGet();
            }
        });
    }

    /**
     * Send a successful gRPC response: HEADERS(200) + DATA(grpc-framed JSON) + TRAILERS(grpc-status=0).
     */
    private void sendGrpcResponse(ChannelHandlerContext ctx, Response response) {
        if (!ctx.channel().isActive()) {
            return;
        }

        // Response headers (HTTP 200, gRPC content-type)
        Http2Headers respHeaders = new DefaultHttp2Headers()
                .status(Http2Constants.STATUS_OK)
                .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.GRPC_JSON_CONTENT_TYPE);
        ctx.write(new DefaultHttp2HeadersFrame(respHeaders));

        // Determine grpc-status based on whether there's an exception
        String grpcStatus;
        String grpcMessage = null;
        Object responseBody;

        if (response.getException() != null) {
            grpcStatus = Http2Constants.GRPC_STATUS_INTERNAL;
            grpcMessage = response.getException().getMessage();
            responseBody = new java.util.LinkedHashMap<>();
            //noinspection unchecked
            ((LinkedHashMap<String, Object>) responseBody).put("error", grpcMessage);
        } else {
            grpcStatus = Http2Constants.GRPC_STATUS_OK;
            responseBody = response.getRawValue();
        }

        // Encode response value as JSON, then gRPC-frame it
        String responseJson = responseBody != null ? JSON.toJSONString(responseBody) : "{}";
        byte[] messageBytes = responseJson.getBytes(StandardCharsets.UTF_8);
        byte[] grpcFrame = GrpcCodec.encodeFrame(messageBytes);
        ctx.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(grpcFrame)));

        // Trailers with grpc-status (END_STREAM)
        Http2Headers trailers = new DefaultHttp2Headers();
        trailers.set(Http2Constants.GRPC_STATUS, grpcStatus);
        if (grpcMessage != null) {
            trailers.set(Http2Constants.GRPC_MESSAGE, grpcMessage);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));
    }

    /**
     * Send a gRPC error: HTTP 200 + TRAILERS with non-zero grpc-status.
     */
    private void sendGrpcError(ChannelHandlerContext ctx, String grpcStatusCode, String message) {
        if (!ctx.channel().isActive()) {
            return;
        }
        // Send HEADERS(200) without END_STREAM
        Http2Headers respHeaders = new DefaultHttp2Headers()
                .status(Http2Constants.STATUS_OK)
                .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.GRPC_JSON_CONTENT_TYPE);
        ctx.write(new DefaultHttp2HeadersFrame(respHeaders));

        // Send empty DATA without END_STREAM
        ctx.write(new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER));

        // Send TRAILERS with grpc-status and grpc-message (END_STREAM)
        Http2Headers trailers = new DefaultHttp2Headers();
        trailers.set(Http2Constants.GRPC_STATUS, grpcStatusCode);
        if (message != null) {
            trailers.set(Http2Constants.GRPC_MESSAGE, message);
        }
        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true));
    }
}
