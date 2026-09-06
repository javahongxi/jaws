package org.hongxi.jaws.transport.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the MCP Streamable HTTP transport.
 * <p>
 * Uses Java's built-in {@link HttpClient} to speak MCP protocol against
 * a real {@link McpServer}, verifying the full lifecycle:
 * initialize → initialized notification → tools/list → tools/call → session delete.
 */
class McpTransportTest {

    private McpServer server;
    private int port;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, "");
        url.addParameter("mcpEndpoint", "/mcp");

        server = new McpServer(url);

        // Register a simple "hello" tool
        server.getToolRegistry().register(new McpToolRegistry.Tool(
                "hello",
                "Say hello to someone",
                Map.of("type", "object",
                        "properties", Map.of("name", Map.of("type", "string", "description", "Name to greet")),
                        "required", List.of("name")),
                args -> "Hello, " + args.get("name") + "!"
        ));

        assertTrue(server.open());
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void initializeHandshake() throws Exception {
        String body = jsonRpcRequest("initialize", 1, Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test-client", "version", "1.0")
        ));

        HttpResponse<String> response = sendPost(body, null);

        assertEquals(200, response.statusCode());
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(sessionId, "Mcp-Session-Id header must be present");

        JSONObject json = JSON.parseObject(response.body());
        assertEquals("2.0", json.getString("jsonrpc"));
        assertEquals(1, json.get("id"));
        assertNotNull(json.get("result"));

        JSONObject result = json.getJSONObject("result");
        assertEquals("2025-03-26", result.getString("protocolVersion"));
        assertNotNull(result.getJSONObject("capabilities"));
        assertEquals("jaws", result.getJSONObject("serverInfo").getString("name"));
    }

    @Test
    void toolsListReturnsRegisteredTools() throws Exception {
        String sessionId = initializeSession();

        // Send initialized notification
        String notifBody = jsonRpcNotification("notifications/initialized", null);
        HttpResponse<String> notifResp = sendPost(notifBody, sessionId);
        assertEquals(202, notifResp.statusCode());

        // Send tools/list request
        String body = jsonRpcRequest("tools/list", 2, Map.of());
        HttpResponse<String> response = sendPost(body, sessionId);

        assertEquals(200, response.statusCode());
        // Response is SSE format
        String responseBody = response.body();
        assertTrue(responseBody.contains("event: message"), "Response should be SSE format");
        assertTrue(responseBody.contains("data: "), "Response should contain data");

        // Extract JSON from SSE frame
        String jsonLine = extractSseData(responseBody);
        JSONObject json = JSON.parseObject(jsonLine);
        assertEquals(2, json.get("id"));

        JSONObject result = json.getJSONObject("result");
        assertNotNull(result);
        var tools = result.getJSONArray("tools");
        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("hello", tools.getJSONObject(0).getString("name"));
        assertEquals("Say hello to someone", tools.getJSONObject(0).getString("description"));
    }

    @Test
    void toolsCallExecutesTool() throws Exception {
        String sessionId = initializeSession();

        // Send initialized notification
        sendPost(jsonRpcNotification("notifications/initialized", null), sessionId);

        // Call the hello tool
        String body = jsonRpcRequest("tools/call", 3, Map.of(
                "name", "hello",
                "arguments", Map.of("name", "Jaws")
        ));
        HttpResponse<String> response = sendPost(body, sessionId);

        assertEquals(200, response.statusCode());
        String jsonLine = extractSseData(response.body());
        JSONObject json = JSON.parseObject(jsonLine);
        assertEquals(3, json.get("id"));

        JSONObject result = json.getJSONObject("result");
        assertNotNull(result);
        var content = result.getJSONArray("content");
        assertNotNull(content);
        assertEquals(1, content.size());
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("Hello, Jaws!", content.getJSONObject(0).getString("text"));
    }

    @Test
    void toolsCallNotFound() throws Exception {
        String sessionId = initializeSession();
        sendPost(jsonRpcNotification("notifications/initialized", null), sessionId);

        String body = jsonRpcRequest("tools/call", 4, Map.of(
                "name", "nonexistent",
                "arguments", Map.of()
        ));
        HttpResponse<String> response = sendPost(body, sessionId);

        assertEquals(200, response.statusCode());
        String jsonLine = extractSseData(response.body());
        JSONObject json = JSON.parseObject(jsonLine);

        // Tool not found returns a JSON-RPC error response
        JSONObject error = json.getJSONObject("error");
        assertNotNull(error, "Should return a JSON-RPC error for unknown tool");
        assertEquals(-32601, error.getIntValue("code"));
        assertTrue(error.getString("message").contains("Tool not found"));
    }

    @Test
    void pingReturnsEmptyResult() throws Exception {
        String sessionId = initializeSession();
        sendPost(jsonRpcNotification("notifications/initialized", null), sessionId);

        String body = jsonRpcRequest("ping", 5, null);
        HttpResponse<String> response = sendPost(body, sessionId);

        assertEquals(200, response.statusCode());
        String jsonLine = extractSseData(response.body());
        JSONObject json = JSON.parseObject(jsonLine);
        assertEquals(5, json.get("id"));
        assertNotNull(json.get("result"));
    }

    @Test
    void deleteSessionTerminatesSession() throws Exception {
        String sessionId = initializeSession();

        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .header("Mcp-Session-Id", sessionId)
                .build();

        HttpResponse<String> response = httpClient.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Subsequent request should fail (session gone)
        String body = jsonRpcRequest("ping", 6, null);
        HttpResponse<String> resp2 = sendPost(body, sessionId);
        assertEquals(400, resp2.statusCode());
    }

    @Test
    void unknownEndpointReturns404() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/unknown"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    void invalidJsonReturnsParseError() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString("not json"))
                .header("Accept", "application/json, text/event-stream")
                .build();

        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("-32700"), "Should return PARSE_ERROR code");
    }

    // ---- Helpers ----

    /**
     * Perform initialize and return the session ID.
     */
    private String initializeSession() throws Exception {
        String body = jsonRpcRequest("initialize", 1, Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test-client", "version", "1.0")
        ));
        HttpResponse<String> response = sendPost(body, null);
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new AssertionError("No session ID returned"));
    }

    private HttpResponse<String> sendPost(String body, String sessionId) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonRpcRequest(String method, Object id, Object params) {
        JSONObject obj = new JSONObject();
        obj.put("jsonrpc", "2.0");
        obj.put("method", method);
        obj.put("id", id);
        if (params != null) {
            obj.put("params", params);
        }
        return obj.toJSONString();
    }

    private static String jsonRpcNotification(String method, Object params) {
        JSONObject obj = new JSONObject();
        obj.put("jsonrpc", "2.0");
        obj.put("method", method);
        if (params != null) {
            obj.put("params", params);
        }
        return obj.toJSONString();
    }

    /**
     * Extract the JSON data from an SSE response body.
     * SSE format: "id: ...\nevent: message\ndata: {...}\n\n"
     */
    private static String extractSseData(String sseBody) {
        for (String line : sseBody.split("\n")) {
            if (line.startsWith("data: ")) {
                return line.substring(6).trim();
            }
        }
        throw new AssertionError("No 'data:' line found in SSE response: " + sseBody);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
