package org.hongxi.jaws.transport.http.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;

/**
 * Codec for MCP JSON-RPC 2.0 messages, covering the two message types
 * used in stateless mode: request (expects a response) and response.
 * <p>
 * This is a lightweight, self-contained codec using fastjson2 — no dependency
 * on the MCP Java SDK schema classes.
 *
 * @author shenhongxi
 */
public final class McpMessageCodec {

    public static final String JSONRPC_VERSION = "2.0";

    // JSON-RPC error codes
    public static final int PARSE_ERROR = -32700;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    // MCP method names
    public static final String METHOD_PING = "ping";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    private McpMessageCodec() {
    }

    // ---- Message types ----

    /**
     * Base sealed interface for all JSON-RPC 2.0 messages.
     */
    public sealed interface Message permits Request, Response {
        String jsonrpc();
    }

    /**
     * A request that expects a response.
     */
    public record Request(
            @JSONField(name = "jsonrpc") String jsonrpc,
            String method,
            Object id,
            Object params) implements Message {
        public Request(String method, Object id, Object params) {
            this(JSONRPC_VERSION, method, id, params);
        }
    }

    /**
     * A response to a request (successful or error).
     */
    public record Response(
            @JSONField(name = "jsonrpc") String jsonrpc,
            Object id,
            Object result,
            Error error) implements Message {

        public static Response result(Object id, Object result) {
            return new Response(JSONRPC_VERSION, id, result, null);
        }

        public static Response error(Object id, Error error) {
            return new Response(JSONRPC_VERSION, id, null, error);
        }
    }

    /**
     * JSON-RPC error object.
     */
    public record Error(int code, String message, Object data) {
        public Error(int code, String message) {
            this(code, message, null);
        }
    }

    // ---- Deserialization ----

    /**
     * Deserialize a JSON string into the appropriate {@link Message} subtype.
     * <ul>
     *   <li>has "method" + "id" → {@link Request}</li>
     *   <li>has "result" or "error" → {@link Response}</li>
     * </ul>
     */
    public static Message decode(String json) {
        JSONObject obj = JSON.parseObject(json);
        if (obj.containsKey("method") && obj.containsKey("id")) {
            return obj.to(Request.class);
        } else if (obj.containsKey("result") || obj.containsKey("error")) {
            return obj.to(Response.class);
        }
        throw new IllegalArgumentException("Cannot deserialize JSON-RPC message: " + json);
    }

    // ---- Serialization ----

    public static String encode(Message message) {
        return JSON.toJSONString(message);
    }

    // ---- Factory helpers ----

    public static Response errorResponse(Object id, int code, String message) {
        return Response.error(id, new Error(code, message));
    }
}
