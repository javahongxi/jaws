package org.hongxi.jaws.transport.mcp;

import com.alibaba.fastjson2.JSON;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Inbound handler for the MCP Streamable HTTP transport.
 * <p>
 * Routes on the single MCP endpoint (default {@code /mcp}):
 * <ul>
 *   <li>{@code POST /mcp} — receive JSON-RPC 2.0 messages from clients</li>
 *   <li>{@code GET /mcp} — establish an SSE listening stream for server pushes</li>
 *   <li>{@code DELETE /mcp} — terminate a session</li>
 * </ul>
 * <p>
 * POST dispatch by JSON-RPC message type:
 * <ul>
 *   <li>{@link McpMessageCodec.Request} — initialize → JSON response; others → SSE stream</li>
 *   <li>{@link McpMessageCodec.Notification} → 202 Accepted</li>
 *   <li>{@link McpMessageCodec.Response} → 202 Accepted</li>
 * </ul>
 * <p>
 * All business logic runs off the event loop on the server executor.
 *
 * @author shenhongxi
 */
public class McpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(McpHandler.class);

    private static final String APPLICATION_JSON = "application/json; charset=utf-8";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String ACCEPT = "Accept";

    private final String mcpEndpoint;
    private final McpSessionManager sessionManager;
    private final McpToolRegistry toolRegistry;
    private final ExecutorService serverExecutor;

    public McpHandler(String mcpEndpoint,
                          McpSessionManager sessionManager,
                          McpToolRegistry toolRegistry,
                          ExecutorService serverExecutor) {
        this.mcpEndpoint = mcpEndpoint;
        this.sessionManager = sessionManager;
        this.toolRegistry = toolRegistry;
        this.serverExecutor = serverExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String path = extractPath(uri);

        if (!mcpEndpoint.equals(path)) {
            sendJsonResponse(ctx, HttpResponseStatus.NOT_FOUND,
                    McpMessageCodec.errorResponse(null, McpMessageCodec.METHOD_NOT_FOUND,
                            "Unknown endpoint: " + path + ". Use " + mcpEndpoint));
            return;
        }

        HttpMethod method = request.method();
        if (HttpMethod.POST.equals(method)) {
            handlePost(ctx, request);
        } else if (HttpMethod.GET.equals(method)) {
            handleGet(ctx, request);
        } else if (HttpMethod.DELETE.equals(method)) {
            handleDelete(ctx, request);
        } else {
            sendStatusResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }
    }

    // ---- POST /mcp ----

    private void handlePost(ChannelHandlerContext ctx, FullHttpRequest request) {
        // Validate Accept header
        String accept = request.headers().get(ACCEPT);
        if (accept == null || (!accept.contains(TEXT_EVENT_STREAM) && !accept.contains("application/json"))) {
            sendStatusResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    "Accept header must include text/event-stream or application/json");
            return;
        }

        // Parse body
        String body = request.content().toString(StandardCharsets.UTF_8);
        McpMessageCodec.Message message;
        try {
            message = McpMessageCodec.decode(body);
        } catch (Exception e) {
            log.warn("Failed to parse JSON-RPC message: {}", e.getMessage());
            sendJsonResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    McpMessageCodec.errorResponse(null, McpMessageCodec.PARSE_ERROR,
                            "Invalid JSON-RPC: " + e.getMessage()));
            return;
        }

        // Dispatch by message type
        if (message instanceof McpMessageCodec.Request req) {
            handleRequest(ctx, request, req);
        } else if (message instanceof McpMessageCodec.Notification notif) {
            handleNotification(ctx, request, notif);
        } else if (message instanceof McpMessageCodec.Response resp) {
            handleClientResponse(ctx, resp);
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest httpRequest,
                                McpMessageCodec.Request request) {
        String methodName = request.method();

        // Initialize is special: creates a session and returns JSON (not SSE)
        if (McpMessageCodec.METHOD_INITIALIZE.equals(methodName)) {
            handleInitialize(ctx, request);
            return;
        }

        // All other requests require an active session
        String resolvedId = extractSessionId(ctx, httpRequest);
        McpSessionManager.McpSession session = resolvedId != null ? sessionManager.getSession(resolvedId) : null;
        if (session == null || !session.isInitialized()) {
            sendJsonResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    McpMessageCodec.errorResponse(request.id(), McpMessageCodec.INVALID_REQUEST,
                            "Session not initialized. Send 'initialize' request first."));
            return;
        }
        final String sessionId = resolvedId;

        // Dispatch to business thread pool
        try {
            serverExecutor.execute(() -> {
                try {
                    McpMessageCodec.Response response = dispatchMethod(session, request);
                    String json = McpMessageCodec.encode(response);
                    // Send as SSE stream (single frame then close)
                    sendSseResponse(ctx, json, sessionId);
                } catch (Exception e) {
                    log.error("Failed to handle MCP request: method={}", methodName, e);
                    McpMessageCodec.Response errorResp = McpMessageCodec.errorResponse(
                            request.id(), McpMessageCodec.INTERNAL_ERROR, e.getMessage());
                    sendSseResponse(ctx, McpMessageCodec.encode(errorResp), sessionId);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("MCP request rejected: server thread pool is full");
            sendJsonResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    McpMessageCodec.errorResponse(request.id(), McpMessageCodec.INTERNAL_ERROR,
                            "Server thread pool is full"));
        }
    }

    /**
     * Handle the initialize handshake. Creates a new session and returns
     * the server capabilities as a JSON response (not SSE).
     */
    private void handleInitialize(ChannelHandlerContext ctx, McpMessageCodec.Request request) {
        McpSessionManager.McpSession session = sessionManager.createSession();

        // Extract client protocol version from params
        if (request.params() instanceof Map<?, ?> params) {
            Object pv = params.get("protocolVersion");
            if (pv instanceof String v) {
                session.setClientProtocolVersion(v);
            }
        }

        // Build InitializeResult
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", McpSessionManager.SUPPORTED_PROTOCOL_VERSION);

        // Server capabilities
        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (toolRegistry.size() > 0) {
            Map<String, Object> tools = new LinkedHashMap<>();
            tools.put("listChanged", true);
            capabilities.put("tools", tools);
        }
        result.put("capabilities", capabilities);

        // Server info
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "jaws");
        serverInfo.put("version", "1.0");
        result.put("serverInfo", serverInfo);

        McpMessageCodec.Response response = McpMessageCodec.Response.result(request.id(), result);
        String json = McpMessageCodec.encode(response);

        // Send JSON response with Mcp-Session-Id header
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, APPLICATION_JSON);
        HttpUtil.setContentLength(httpResponse, body.length);
        httpResponse.headers().set(McpSessionManager.MCP_SESSION_ID, session.getId());
        httpResponse.headers().set(McpSessionManager.PROTOCOL_VERSION,
                McpSessionManager.SUPPORTED_PROTOCOL_VERSION);
        ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);

        // Store session ID on channel for subsequent requests on same connection
        ctx.channel().attr(io.netty.util.AttributeKey.<String>valueOf(McpSessionManager.MCP_SESSION_ID))
                .set(session.getId());

        log.info("MCP session initialized: id={}", session.getId());
    }

    /**
     * Dispatch an MCP method (tools/list, tools/call, ping) and return the response.
     */
    private McpMessageCodec.Response dispatchMethod(McpSessionManager.McpSession session,
                                                     McpMessageCodec.Request request) {
        String method = request.method();
        Object id = request.id();

        return switch (method) {
            case McpMessageCodec.METHOD_PING ->
                    McpMessageCodec.Response.result(id, new LinkedHashMap<>());

            case McpMessageCodec.METHOD_TOOLS_LIST -> {
                List<Map<String, Object>> toolList = new ArrayList<>();
                for (McpToolRegistry.Tool tool : toolRegistry.getAll().values()) {
                    Map<String, Object> toolSpec = new LinkedHashMap<>();
                    toolSpec.put("name", tool.name());
                    toolSpec.put("description", tool.description());
                    toolSpec.put("inputSchema", tool.inputSchema());
                    toolList.add(toolSpec);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("tools", toolList);
                yield McpMessageCodec.Response.result(id, result);
            }

            case McpMessageCodec.METHOD_TOOLS_CALL -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = request.params() instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Collections.emptyMap();
                String toolName = (String) params.get("name");
                if (toolName == null) {
                    yield McpMessageCodec.errorResponse(id, McpMessageCodec.INVALID_PARAMS,
                            "'name' is required in tools/call params");
                }
                McpToolRegistry.Tool tool = toolRegistry.get(toolName);
                if (tool == null) {
                    yield McpMessageCodec.errorResponse(id, McpMessageCodec.METHOD_NOT_FOUND,
                            "Tool not found: " + toolName);
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a
                            ? (Map<String, Object>) a : Collections.emptyMap();
                    Object toolResult = tool.executor().execute(args);

                    // MCP tool result format: { content: [{ type: "text", text: "..." }] }
                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("type", "text");
                    content.put("text", toolResult instanceof String s ? s : JSON.toJSONString(toolResult));

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("content", List.of(content));
                    yield McpMessageCodec.Response.result(id, result);
                } catch (Exception e) {
                    log.error("Tool execution failed: {}", toolName, e);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())));
                    result.put("isError", true);
                    yield McpMessageCodec.Response.result(id, result);
                }
            }

            default -> McpMessageCodec.errorResponse(id, McpMessageCodec.METHOD_NOT_FOUND,
                    "Unknown method: " + method);
        };
    }

    // ---- Notification handling ----

    private void handleNotification(ChannelHandlerContext ctx, FullHttpRequest request,
                                     McpMessageCodec.Notification notification) {
        String method = notification.method();

        if (McpMessageCodec.METHOD_NOTIFICATION_INITIALIZED.equals(method)) {
            // Mark session as fully initialized
            String sessionId = extractSessionId(ctx, request);
            if (sessionId != null) {
                McpSessionManager.McpSession session = sessionManager.getSession(sessionId);
                if (session != null) {
                    session.setInitialized(true);
                    log.debug("MCP session fully initialized: {}", sessionId);
                }
            }
        } else {
            log.debug("Received MCP notification: method={}", method);
        }

        // Notifications always get 202 Accepted
        sendStatusResponse(ctx, HttpResponseStatus.ACCEPTED, "Accepted");
    }

    // ---- Client response handling ----

    private void handleClientResponse(ChannelHandlerContext ctx, McpMessageCodec.Response response) {
        // Client responses (e.g. to sampling requests) are accepted
        log.debug("Received MCP client response: id={}", response.id());
        sendStatusResponse(ctx, HttpResponseStatus.ACCEPTED, "Accepted");
    }

    // ---- GET /mcp (SSE listening stream) ----

    private void handleGet(ChannelHandlerContext ctx, FullHttpRequest request) {
        String accept = request.headers().get(ACCEPT);
        if (accept == null || !accept.contains(TEXT_EVENT_STREAM)) {
            sendStatusResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    "Accept header must include text/event-stream");
            return;
        }

        String sessionId = extractSessionId(ctx, request);
        if (sessionId == null) {
            sendStatusResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    "Mcp-Session-Id header is required");
            return;
        }

        McpSessionManager.McpSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            sendStatusResponse(ctx, HttpResponseStatus.NOT_FOUND, "Session not found");
            return;
        }

        // Set up SSE listening stream
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, TEXT_EVENT_STREAM);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        ctx.writeAndFlush(response);

        // Store the channel context on the session for server-initiated pushes
        session.setListeningCtx(ctx);

        log.debug("MCP SSE listening stream established: session={}", sessionId);
        // Connection stays open — do NOT close or add ChannelFutureListener.CLOSE
    }

    // ---- DELETE /mcp ----

    private void handleDelete(ChannelHandlerContext ctx, FullHttpRequest request) {
        String sessionId = extractSessionId(ctx, request);
        if (sessionId == null) {
            sendStatusResponse(ctx, HttpResponseStatus.BAD_REQUEST,
                    "Mcp-Session-Id header is required");
            return;
        }

        McpSessionManager.McpSession removed = sessionManager.removeSession(sessionId);
        if (removed == null) {
            sendStatusResponse(ctx, HttpResponseStatus.NOT_FOUND, "Session not found");
        } else {
            log.info("MCP session terminated via DELETE: {}", sessionId);
            sendStatusResponse(ctx, HttpResponseStatus.OK, "OK");
        }
    }

    // ---- Response helpers ----

    /**
     * Send a chunked SSE response: headers + single SSE frame + end.
     */
    private void sendSseResponse(ChannelHandlerContext ctx, String json, String messageId) {
        // Send response headers (chunked)
        HttpResponse headers = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        headers.headers().set(HttpHeaderNames.CONTENT_TYPE, TEXT_EVENT_STREAM);
        headers.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        headers.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        ctx.write(headers);

        // Write SSE frame
        ctx.write(SseEncoder.encodeFrame(json, messageId));

        // End chunked response
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                .addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Send a JSON response (used for initialize and errors).
     */
    private void sendJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status,
                                  McpMessageCodec.Response response) {
        String json = McpMessageCodec.encode(response);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, APPLICATION_JSON);
        HttpUtil.setContentLength(httpResponse, body.length);
        ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendStatusResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        sendStatusResponse(ctx, status, message, false);
    }

    private void sendStatusResponse(ChannelHandlerContext ctx, HttpResponseStatus status,
                                    String message, boolean keepAlive) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        HttpUtil.setContentLength(response, body.length);
        if (keepAlive) {
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ---- Utility ----

    private String extractSessionId(ChannelHandlerContext ctx, FullHttpRequest request) {
        // First try channel attribute (set during initialize on same connection)
        io.netty.util.AttributeKey<String> key =
                io.netty.util.AttributeKey.valueOf(McpSessionManager.MCP_SESSION_ID);
        String sessionId = ctx.channel().attr(key).get();
        if (sessionId != null) {
            return sessionId;
        }
        // Then try header
        if (request != null) {
            sessionId = request.headers().get(McpSessionManager.MCP_SESSION_ID);
            if (sessionId != null) {
                // Cache on channel for subsequent requests
                ctx.channel().attr(key).set(sessionId);
            }
        }
        return sessionId;
    }

    private static String extractPath(String uri) {
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("MCP handler unexpected error", cause);
        if (ctx.channel().isActive()) {
            sendStatusResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error: " + cause.getMessage());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Clean up session reference on this channel
        io.netty.util.AttributeKey<String> key =
                io.netty.util.AttributeKey.valueOf(McpSessionManager.MCP_SESSION_ID);
        String sessionId = ctx.channel().attr(key).getAndSet(null);
        if (sessionId != null) {
            McpSessionManager.McpSession session = sessionManager.getSession(sessionId);
            if (session != null && session.getListeningCtx() == ctx) {
                session.setListeningCtx(null);
            }
        }
        super.channelInactive(ctx);
    }
}
