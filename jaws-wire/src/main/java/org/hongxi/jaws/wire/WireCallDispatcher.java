package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.wire.health.HealthCheckRequest;
import org.hongxi.jaws.wire.health.HealthCheckResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
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
        public void dispatch(ChannelHandlerContext ctx, ByteBuf frameData, WireStreamServerHandler serverHandler) {
            final WireMethodHandler methodHandler = this.handler;
            if (methodHandler == null) {
                serverHandler.sendError(ctx, WireConstants.STATUS_NOT_FOUND, "Method not found: " + serverHandler.path);
                return;
            }
            final WireCallContext callContext = WireCallContext.of(serverHandler.attachments);

            ByteBuf frame = null;
            try {
                if (serverHandler.isDeadlineExceeded()) {
                    serverHandler.sendError(ctx, WireStatus.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
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

                if (methodHandler.methodType() == WireMethodHandler.MethodType.SERVER_STREAMING) {
                    Flow.Publisher<Message> publisher = methodHandler.handleStream(request, callContext);
                    serverHandler.dispatchStream(ctx, publisher);
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
            // Provider pipeline mode defers path validation to dispatch time
            return true;
        }

        @Override
        public void dispatch(ChannelHandlerContext ctx, ByteBuf frameData, WireStreamServerHandler serverHandler) {
            final String svcName = this.serviceName;
            final String mName = this.methodName;
            final Map<String, String> callAttachments = serverHandler.attachments;

            ByteBuf frame = null;
            try {
                if (serverHandler.isDeadlineExceeded()) {
                    serverHandler.sendError(ctx, WireStatus.STATUS_DEADLINE_EXCEEDED, "Deadline exceeded");
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
                serverHandler.dispatchStream(ctx, publisher);
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
}
