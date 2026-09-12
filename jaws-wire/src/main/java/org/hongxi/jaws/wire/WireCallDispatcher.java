package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.util.AttributeKey;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Strategy interface that encapsulates the dispatch-mode differences between
 * the two server operating modes:
 * <ol>
 *   <li><b>Direct API</b> — a {@link WireHandlerRegistry} routes the gRPC path
 *       to a typed {@link WireMethodHandler} ({@link HandlerCallDispatcher})</li>
 *   <li><b>Provider pipeline</b> — a Jaws {@link MessageHandler} pipeline bridges
 *       raw protobuf bytes through the standard request/response model
 *       ({@link ProviderCallDispatcher})</li>
 * </ol>
 * The {@link WireStreamServerHandler} owns the gRPC wire mechanics (frame
 * accumulation, header parsing, response writing, deadline/cancellation
 * handling, streaming dispatch, lifecycle) and delegates only the
 * mode-specific routing and invocation to this strategy.
 *
 * @author shenhongxi
 * @see WireStreamServerHandler
 */
sealed interface WireCallDispatcher
        permits WireCallDispatcher.HandlerCallDispatcher,
                WireCallDispatcher.ProviderCallDispatcher {

    /**
     * Resolve the request path after protocol headers have been parsed.
     * Called once per stream, before DATA accumulation begins.
     *
     * @param ctx   the stream channel context
     * @param path  the request path (never null)
     * @return {@code true} when the path was resolved successfully;
     *         {@code false} when no handler exists for the path, so the
     *         caller should immediately reject with NOT_FOUND
     */
    boolean resolvePath(ChannelHandlerContext ctx, String path);

    /**
     * Decode the request from the accumulated gRPC frame, invoke the business
     * serverHandler, and write the response through the stream serverHandler's helpers
     * ({@link WireStreamServerHandler#sendResponseHeaders},
     * {@link WireStreamServerHandler#sendTrailers},
     * {@link WireStreamServerHandler#dispatchStream},
     * {@link WireStreamServerHandler#sendUnaryResponse}).
     *
     * @param ctx       the stream channel context
     * @param frameData the accumulated gRPC frame data (caller releases)
     * @param serverHandler   the owning stream serverHandler
     */
    void dispatch(ChannelHandlerContext ctx, ByteBuf frameData, WireStreamServerHandler serverHandler);

    /**
     * @return {@code true} when the resolved path is a bidirectional streaming method
     */
    default boolean isBidiStream() {
        return false;
    }

    /**
     * @return {@code true} when the resolved path is a client-streaming method
     */
    default boolean isClientStream() {
        return false;
    }

    /**
     * @return the protobuf parser for the items of a streaming request (bidi or
     *         client-streaming), or {@code null} when the method takes no request
     *         stream or the parser is not available
     */
    default Parser<? extends Message> getRequestStreamParser() {
        return null;
    }

    /**
     * Dispatch a bidirectional streaming call. The first gRPC frame has already
     * been extracted as {@code firstFrame}; subsequent frames are pushed into
     * {@code requestStream}, which the handler subscribes to.
     *
     * @param ctx              the stream channel context
     * @param firstFrame       the first gRPC frame data (caller releases)
     * @param serverHandler    the owning stream serverHandler
     * @param requestStream    request stream the handler consumes
     */
    void dispatchBidiStream(ChannelHandlerContext ctx, ByteBuf firstFrame,
                            WireStreamServerHandler serverHandler,
                            StreamSource<Object> requestStream);

    /**
     * Dispatch a client-streaming call. The first gRPC frame has already been
     * extracted and pushed into {@code requestStream}; the handler consumes the
     * whole stream and returns a single response message.
     *
     * @param ctx              the stream channel context
     * @param firstFrame       the first gRPC frame data (caller releases)
     * @param serverHandler    the owning stream serverHandler
     * @param requestStream    request stream the handler consumes
     */
    void dispatchClientStream(ChannelHandlerContext ctx, ByteBuf firstFrame,
                              WireStreamServerHandler serverHandler,
                              StreamSource<Object> requestStream);

    // ========================================================================
    // Direct API mode — registry-based routing to typed WireMethodHandler
    // ========================================================================

    /**
     * Registry-mode dispatcher: resolves the gRPC path against a
     * {@link WireHandlerRegistry} to find a typed {@link WireMethodHandler},
     * decodes the protobuf request, and invokes the handler directly.
     */
    final class HandlerCallDispatcher implements WireCallDispatcher {
        private static final Logger log = LoggerFactory.getLogger(HandlerCallDispatcher.class);

        private final WireHandlerRegistry registry;
        private WireMethodHandler handler;

        HandlerCallDispatcher(WireHandlerRegistry registry) {
            this.registry = registry;
        }

        @Override
        public boolean resolvePath(ChannelHandlerContext ctx, String path) {
            this.handler = registry.resolve(path);
            return this.handler != null;
        }

        @Override
        public boolean isBidiStream() {
            return handler != null && handler.methodType() == WireMethodHandler.MethodType.BIDIRECTIONAL;
        }

        @Override
        public boolean isClientStream() {
            return handler != null && handler.methodType() == WireMethodHandler.MethodType.CLIENT_STREAM;
        }

        @Override
        public Parser<? extends Message> getRequestStreamParser() {
            return handler != null ? handler.getRequestParser() : null;
        }

        @Override
        public void dispatchBidiStream(ChannelHandlerContext ctx, ByteBuf firstFrame,
                                       WireStreamServerHandler serverHandler,
                                       StreamSource<Object> requestStream) {
            final WireMethodHandler methodHandler = this.handler;
            final WireCallContext callContext = buildContextWithConnectionId(ctx, serverHandler);
            try {
                // The transport carries items as Object; a wire handler declares
                // them as protobuf Message, so narrowing the type argument here
                // is inherent to this boundary.
                // noinspection unchecked
                StreamSource<Message> requestItems = (StreamSource<Message>) (StreamSource<?>) requestStream;
                StreamSource<Message> responseSource = methodHandler.handleBidiStream(requestItems, callContext);
                serverHandler.dispatchStream(ctx, responseSource);
            } catch (Exception e) {
                log.error("Wire bidi invoke failed: path={}", serverHandler.path, e);
                if (!serverHandler.canceled && ctx.channel().isActive()) {
                    serverHandler.sendError(ctx, WireStatus.fromThrowable(e), "Invoke failed: " + e.getMessage());
                }
            }
            // Note: firstFrame is null — already decoded and released by processStreamFrames
        }

        @Override
        public void dispatchClientStream(ChannelHandlerContext ctx, ByteBuf firstFrame,
                                         WireStreamServerHandler serverHandler,
                                         StreamSource<Object> requestStream) {
            final WireMethodHandler methodHandler = this.handler;
            final WireCallContext callContext = WireCallContext.of(serverHandler.attachments);
            try {
                // noinspection unchecked
                StreamSource<Message> requestItems = (StreamSource<Message>) (StreamSource<?>) requestStream;
                Message response = methodHandler.handleClientStream(requestItems, callContext);
                serverHandler.sendUnaryResponse(ctx, response);
            } catch (Exception e) {
                log.error("Wire client-stream invoke failed: path={}", serverHandler.path, e);
                if (!serverHandler.canceled && ctx.channel().isActive()) {
                    serverHandler.sendError(ctx, WireStatus.fromThrowable(e), "Invoke failed: " + e.getMessage());
                }
            }
        }

        @Override
        public void dispatch(ChannelHandlerContext ctx, ByteBuf frameData, WireStreamServerHandler serverHandler) {
            final WireMethodHandler methodHandler = this.handler;
            if (methodHandler == null) {
                serverHandler.sendError(ctx, WireConstants.STATUS_NOT_FOUND, "Method not found: " + serverHandler.path);
                return;
            }
            final WireCallContext callContext = buildContextWithConnectionId(ctx, serverHandler);

            ByteBuf frame = null;
            try {
                if (serverHandler.isDeadlineExceeded()) {
                    serverHandler.sendError(ctx, WireConstants.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
                    return;
                }

                frame = WireFrameCodec.tryExtractFrame(frameData);
                if (frame == null) {
                    serverHandler.sendError(ctx, WireConstants.STATUS_INTERNAL, "Incomplete gRPC frame");
                    return;
                }

                Message request;
                try {
                    request = WireFrameCodec.decode(frame, methodHandler.getRequestParser(), serverHandler.requestEncoding);
                } catch (IllegalArgumentException e) {
                    serverHandler.sendError(ctx, WireConstants.STATUS_UNIMPLEMENTED, e.getMessage());
                    return;
                }

                // Run the interceptor chain (if any) before invoking the handler
                List<WireServerInterceptor> interceptors = registry.getInterceptors();
                if (!interceptors.isEmpty()
                        && methodHandler.methodType() == WireMethodHandler.MethodType.UNARY) {
                    InterceptorCall chain = new InterceptorCall(
                            interceptors, 0, serverHandler, methodHandler, callContext,
                            serverHandler.path);
                    chain.intercept(request);
                } else if (methodHandler.methodType() == WireMethodHandler.MethodType.SERVER_STREAM) {
                    StreamSource<Message> source = methodHandler.handleStream(request, callContext);
                    serverHandler.dispatchStream(ctx, source);
                } else {
                    Message response = methodHandler.handle(request, callContext);
                    serverHandler.sendUnaryResponse(ctx, response);
                }
            } catch (Exception e) {
                log.error("Wire invoke failed: path={}", serverHandler.path, e);
                if (!serverHandler.canceled && ctx.channel().isActive()) {
                    serverHandler.sendError(ctx, WireStatus.fromThrowable(e), "Invoke failed: " + e.getMessage());
                }
            } finally {
                if (frame != null) {
                    frame.release();
                }
            }
        }

        /**
         * Build a {@link WireCallContext} that includes the connection-level ID
         * (if available) from the parent channel attribute.  The parent channel
         * (TCP connection) may carry a {@code CONNECTION_ID} attribute set by a
         * connection-level handler; this method merges it into the per-call
         * attachments so business handlers can distinguish connections from the
         * same client IP.
         */
        private WireCallContext buildContextWithConnectionId(
                ChannelHandlerContext ctx, WireStreamServerHandler serverHandler) {
            io.netty.channel.Channel parent = ctx.channel().parent();
            if (parent != null) {
                String connectionId = parent.attr(
                        AttributeKey.<String>valueOf(WireConstants.CONNECTION_ID)).get();
                if (connectionId != null) {
                    Map<String, String> merged = new HashMap<>(serverHandler.attachments);
                    merged.put(WireConstants.CONNECTION_ID, connectionId);
                    return WireCallContext.of(merged);
                }
            }
            return WireCallContext.of(serverHandler.attachments);
        }
    }

    // ========================================================================
    // Provider pipeline mode — bridges to the Jaws MessageHandler pipeline
    // ========================================================================

    /**
     * SPI-mode dispatcher: parses the gRPC path into service/method names,
     * extracts raw protobuf bytes from the gRPC frame, builds a Jaws
     * {@link DefaultRequest}, and dispatches through the {@link MessageHandler}
     * pipeline. Also handles the standard {@code grpc.health.v1} health check
     * inline as a protocol concern.
     */
    final class ProviderCallDispatcher implements WireCallDispatcher {
        private static final Logger log = LoggerFactory.getLogger(ProviderCallDispatcher.class);

        /** gRPC path for the standard health Check method. */
        private static final String HEALTH_CHECK_PATH =
                "/" + WireHealthService.SERVICE_NAME + "/Check";

        private final MessageHandler messageHandler;
        private final WireHealthService healthService;

        private String serviceName;
        private String methodName;
        private boolean bidiStream;
        private boolean clientStream;

        ProviderCallDispatcher(MessageHandler messageHandler, WireHealthService healthService) {
            this.messageHandler = messageHandler;
            this.healthService = healthService;
        }

        @Override
        public boolean resolvePath(ChannelHandlerContext ctx, String path) {
            // Parse gRPC path: /{serviceName}/{methodName}
            if (path.startsWith("/")) {
                String trimmed = path.substring(1);
                int slashIdx = trimmed.indexOf('/');
                if (slashIdx > 0) {
                    serviceName = trimmed.substring(0, slashIdx);
                    methodName = trimmed.substring(slashIdx + 1);
                }
            }
            // Resolve streaming flags from the WireMessageHandler's proto types
            if (messageHandler instanceof WireMessageHandler wmh && methodName != null) {
                this.bidiStream = wmh.isBidiStream(methodName);
                this.clientStream = wmh.isClientStream(methodName);
            }
            // Provider pipeline mode defers path validation to dispatch time
            return true;
        }

        @Override
        public boolean isBidiStream() {
            return bidiStream;
        }

        @Override
        public boolean isClientStream() {
            return clientStream;
        }

        @Override
        public void dispatchBidiStream(ChannelHandlerContext ctx, ByteBuf firstFrame,
                                       WireStreamServerHandler serverHandler,
                                       StreamSource<Object> requestStream) {
            final String svcName = this.serviceName;
            final String mName = this.methodName;
            final Map<String, String> callAttachments = serverHandler.attachments;
            try {
                // Build Jaws request without arguments — all request items
                // flow through the requestStream publisher (firstFrame is null
                // because it was already decoded and added to the publisher
                // by processStreamFrames).
                DefaultRequest jawsRequest = new DefaultRequest();
                jawsRequest.setInterfaceName(svcName);
                jawsRequest.setMethodName(toJavaMethodName(mName));
                jawsRequest.setArguments(new Object[0]);
                for (Map.Entry<String, String> entry : callAttachments.entrySet()) {
                    jawsRequest.setAttachment(entry.getKey(), entry.getValue());
                }

                RpcContext.init(jawsRequest);
                try {
                    StreamSource<Object> responseSource =
                            messageHandler.handleStream(jawsRequest, requestStream);
                    serverHandler.dispatchStream(ctx, responseSource);
                } finally {
                    // Note: RpcContext.destroy() deferred to response completion
                }
            } catch (Exception e) {
                log.error("Wire Provider bidi invoke failed: path={}", serverHandler.path, e);
                if (!serverHandler.canceled && ctx.channel().isActive()) {
                    serverHandler.sendError(ctx, WireStatus.fromThrowable(e), "Invoke failed: " + e.getMessage());
                }
            }
            // Note: firstFrame is null — already decoded and released by processStreamFrames
        }

        @Override
        public void dispatchClientStream(ChannelHandlerContext ctx, ByteBuf firstFrame,
                                         WireStreamServerHandler serverHandler,
                                         StreamSource<Object> requestStream) {
            // Client-streaming dispatch is identical to bidi in Provider mode:
            // both call handleStream(request, requestStream) which returns a
            // StreamSource wrapping the single result. dispatchStream handles
            // the single-item Source correctly.
            dispatchBidiStream(ctx, firstFrame, serverHandler, requestStream);
        }

        private static String toJavaMethodName(String grpcMethodName) {
            if (grpcMethodName == null || grpcMethodName.isEmpty()) {
                return grpcMethodName;
            }
            return Character.toLowerCase(grpcMethodName.charAt(0)) + grpcMethodName.substring(1);
        }

        @Override
        public void dispatch(ChannelHandlerContext ctx, ByteBuf frameData, WireStreamServerHandler serverHandler) {
            final String svcName = this.serviceName;
            final String mName = this.methodName;
            final Map<String, String> callAttachments = serverHandler.attachments;

            ByteBuf frame = null;
            try {
                if (serverHandler.isDeadlineExceeded()) {
                    serverHandler.sendError(ctx, WireConstants.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
                    return;
                }

                frame = WireFrameCodec.tryExtractFrame(frameData);
                if (frame == null) {
                    serverHandler.sendError(ctx, WireConstants.STATUS_INTERNAL, "Incomplete gRPC frame");
                    return;
                }

                // Health check is a protocol concern handled before the
                // business pipeline; no Jaws-side registration needed
                if (HEALTH_CHECK_PATH.equals(serverHandler.path)) {
                    dispatchHealthCheck(ctx, frame, serverHandler);
                    return;
                }

                // Extract raw protobuf bytes, decompressing when the frame is compressed
                byte[] protobufBytes;
                try {
                    protobufBytes = WireFrameCodec.extractPayload(frame, serverHandler.requestEncoding);
                } catch (IllegalArgumentException e) {
                    serverHandler.sendError(ctx, WireConstants.STATUS_UNIMPLEMENTED, e.getMessage());
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

                try {
                    CompletableFuture<Object> future = messageHandler.handleAsync(jawsRequest);
                    if (serverHandler.deadlineMs > 0) {
                        // Honor the caller's deadline: on expiry fail the stream with the
                        // jaws timeout error code, which maps to grpc-status DEADLINE_EXCEEDED
                        future = future.orTimeout(serverHandler.remainingDeadlineMs(), TimeUnit.MILLISECONDS);
                    }

                    if (future.isDone()) {
                        // Sync path: future completed immediately (most business methods
                        // are synchronous) — process inline on the event loop thread
                        try {
                            handleDispatchResult(future.join(), ctx, serverHandler);
                        } finally {
                            RpcContext.destroy();
                        }
                    } else {
                        // Async path: attach a non-blocking callback so the event loop
                        // thread is never blocked by future.join()
                        future.whenComplete((result, throwable) -> {
                            try {
                                if (throwable != null) {
                                    Throwable cause = throwable instanceof CompletionException
                                            ? throwable.getCause() : throwable;
                                    throw new RuntimeException("Provider async call failed", cause);
                                }
                                handleDispatchResult(result, ctx, serverHandler);
                            } catch (Exception e) {
                                log.error("Wire Provider async invoke failed: path={}", serverHandler.path, e);
                                if (!serverHandler.canceled && ctx.channel().isActive()) {
                                    serverHandler.sendError(ctx, WireStatus.fromThrowable(e),
                                            "Invoke failed: " + e.getMessage());
                                }
                            }
                            // Note: RpcContext.destroy() is intentionally omitted here —
                            // RpcContext uses ThreadLocal and was initialized on the event
                            // loop thread; the completing thread may differ for truly
                            // async methods, and the context is no longer needed at this point
                        });
                    }
                } catch (Exception e) {
                    RpcContext.destroy();
                    throw e;
                }
            } catch (Exception e) {
                log.error("Wire Provider invoke failed: path={}", serverHandler.path, e);
                if (!serverHandler.canceled && ctx.channel().isActive()) {
                    // Map the failure class to grpc-status so standard gRPC clients
                    // see retryable (UNAVAILABLE) / deadline (DEADLINE_EXCEEDED)
                    // semantics instead of a blanket INTERNAL
                    serverHandler.sendError(ctx, WireStatus.fromThrowable(e), "Invoke failed: " + e.getMessage());
                }
            } finally {
                if (frame != null) {
                    frame.release();
                }
            }
        }

        /**
         * Process the dispatch result: unwrap the Response, check for errors,
         * and route to either streaming or unary response writing.
         */
        private void handleDispatchResult(Object result, ChannelHandlerContext ctx,
                                          WireStreamServerHandler serverHandler) {
            if (serverHandler.canceled || !ctx.channel().isActive()) {
                return;
            }

            // The result is typically a Response wrapping the business return value.
            // For streaming methods, the wrapped value is a StreamSource.
            Object value = result;
            if (result instanceof Response response) {
                if (response.getThrowable() != null) {
                    throw new RuntimeException("Provider error", response.getThrowable());
                }
                value = response.getRawValue();
            }

            if (value instanceof StreamSource<?> source) {
                // Server streaming: subscribe and emit each Message as a DATA frame
                serverHandler.dispatchStream(ctx, source);
            } else {
                // Unary: single response Message
                Message responseMessage = extractMessage(result);
                serverHandler.sendUnaryResponse(ctx, responseMessage);
            }
        }

        private Message extractMessage(Object result) {
            if (result instanceof DefaultResponse dr) {
                Object value = dr.getRawValue();
                if (value instanceof Message msg) {
                    return msg;
                }
                throw new RuntimeException("Wire Provider expected protobuf Message response but got: "
                        + (value != null ? value.getClass().getName() : "null"));
            } else if (result instanceof Message msg) {
                return msg;
            }
            throw new RuntimeException("Wire Provider unexpected result type: "
                    + (result != null ? result.getClass().getName() : "null"));
        }

        /**
         * Handle {@code grpc.health.v1.Health/Check} inline: decode the request,
         * look up the status, and write the response. Unknown services return
         * NOT_FOUND per the gRPC health-checking spec.
         */
        private void dispatchHealthCheck(ChannelHandlerContext ctx, ByteBuf frame, WireStreamServerHandler serverHandler) {
            try {
                HealthCheckRequest request = WireFrameCodec.decode(
                        frame, HealthCheckRequest.parser(), serverHandler.requestEncoding);
                HealthCheckResponse.ServingStatus status =
                        healthService.getStatus(request.getService());
                if (status == null) {
                    serverHandler.sendError(ctx, WireConstants.STATUS_NOT_FOUND,
                            "Unknown health service: " + request.getService());
                    return;
                }
                HealthCheckResponse response = HealthCheckResponse.newBuilder()
                        .setStatus(status).build();
                serverHandler.sendResponseHeaders(ctx);
                ByteBuf responseFrame = WireFrameCodec.encode(
                        response, ctx.alloc(), serverHandler.compression);
                ctx.write(new DefaultHttp2DataFrame(responseFrame, false));
                serverHandler.sendTrailers(ctx, WireConstants.STATUS_OK, null);
            } catch (Exception e) {
                log.error("Wire Provider health check failed: path={}", serverHandler.path, e);
                serverHandler.sendError(ctx, WireConstants.STATUS_INTERNAL,
                        "Health check failed: " + e.getMessage());
            }
        }
    }

    // ========================================================================
    // Interceptor chain for Direct API mode
    // ========================================================================

    /**
     * Walks the interceptor chain: each interceptor calls {@code next(request)}
     * to hand off to the next interceptor; the last {@code next()} invokes the
     * actual {@link WireMethodHandler}. Any interceptor can short-circuit via
     * {@code respond()} or {@code close()}.
     */
    final class InterceptorCall implements WireServerInterceptor.Call {
        private final List<WireServerInterceptor> interceptors;
        private final int index;
        private final WireStreamServerHandler serverHandler;
        private final WireMethodHandler methodHandler;
        private final WireCallContext callContext;
        private final String path;

        InterceptorCall(List<WireServerInterceptor> interceptors, int index,
                        WireStreamServerHandler serverHandler, WireMethodHandler methodHandler,
                        WireCallContext callContext, String path) {
            this.interceptors = interceptors;
            this.index = index;
            this.serverHandler = serverHandler;
            this.methodHandler = methodHandler;
            this.callContext = callContext;
            this.path = path;
        }

        void intercept(Message request) {
            interceptors.get(index).intercept(request, this);
        }

        @Override
        public WireCallContext context() {
            return callContext;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public void next(Message request) {
            if (index + 1 < interceptors.size()) {
                // Delegate to the next interceptor
                InterceptorCall next = new InterceptorCall(
                        interceptors, index + 1, serverHandler, methodHandler,
                        callContext, path);
                next.intercept(request);
            } else {
                // Last interceptor called next() — invoke the actual handler
                Message response = methodHandler.handle(request, callContext);
                respond(response);
            }
        }

        @Override
        public void respond(Message response) {
            serverHandler.sendUnaryResponse(
                    serverHandler.ctx(), response);
        }

        @Override
        public void close(int status, String message) {
            serverHandler.sendError(serverHandler.ctx(), status, message);
        }
    }
}
