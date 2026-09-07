package org.hongxi.jaws.transport.http.mcp;

import com.alibaba.fastjson2.JSON;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * MCP Stateless Streamable HTTP protocol handler (spec 2026-07-28).
 * <p>
 * This is a helper class used by {@link org.hongxi.jaws.transport.http.HttpRequestHandler}
 * to handle {@code POST /mcp} requests. It is not a standalone Netty handler —
 * it delegates response writing back to the caller via
 * {@link #handlePost(ChannelHandlerContext, FullHttpRequest)}.
 * <p>
 * Stateless mode: no sessions, no initialize handshake,
 * no GET stream, no DELETE. Each request is independent.
 *
 * @author shenhongxi
 * @see <a href="https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http">MCP Streamable HTTP spec</a>
 */
public class McpHandler {
    private static final Logger log = LoggerFactory.getLogger(McpHandler.class);

    private static final String APPLICATION_JSON = "application/json; charset=utf-8";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String ACCEPT = "Accept";

    private final McpToolRegistry toolRegistry;
    private final ExecutorService serverExecutor;

    public McpHandler(McpToolRegistry toolRegistry, ExecutorService serverExecutor) {
        this.toolRegistry = toolRegistry;
        this.serverExecutor = serverExecutor;
    }

    /**
     * Handle a POST /mcp request. Parses the JSON-RPC message and dispatches
     * to the appropriate handler.
     */
    public void handlePost(ChannelHandlerContext ctx, FullHttpRequest request) {
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
            handleRequest(ctx, req);
        } else {
            // Response or other — just accept
            sendStatusResponse(ctx, HttpResponseStatus.ACCEPTED, "Accepted");
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, McpMessageCodec.Request request) {
        // Dispatch to business thread pool
        try {
            serverExecutor.execute(() -> {
                try {
                    McpMessageCodec.Response response = dispatchMethod(request);
                    sendJsonResponse(ctx, HttpResponseStatus.OK, response);
                } catch (Exception e) {
                    log.error("Failed to handle MCP request: method={}", request.method(), e);
                    McpMessageCodec.Response errorResp = McpMessageCodec.errorResponse(
                            request.id(), McpMessageCodec.INTERNAL_ERROR, e.getMessage());
                    sendJsonResponse(ctx, HttpResponseStatus.OK, errorResp);
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
     * Dispatch an MCP method (tools/list, tools/call, ping) and return the response.
     */
    private McpMessageCodec.Response dispatchMethod(McpMessageCodec.Request request) {
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
                Map<String, Object> params;
                if (request.params() instanceof Map<?, ?> m) {
                    //noinspection unchecked
                    params = (Map<String, Object>) m;
                } else {
                    params = Collections.emptyMap();
                }
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
                    Map<String, Object> args;
                    if (params.get("arguments") instanceof Map<?, ?> a) {
                        //noinspection unchecked
                        args = (Map<String, Object>) a;
                    } else {
                        args = Collections.emptyMap();
                    }
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

    // ---- Response helpers ----

    private static void sendJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status,
                                         McpMessageCodec.Response response) {
        String json = McpMessageCodec.encode(response);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, APPLICATION_JSON);
        HttpUtil.setContentLength(httpResponse, body.length);
        ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
    }

    private static void sendStatusResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        HttpUtil.setContentLength(response, body.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
