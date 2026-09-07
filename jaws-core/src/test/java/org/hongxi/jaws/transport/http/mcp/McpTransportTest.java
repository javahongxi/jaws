package org.hongxi.jaws.transport.http.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.hongxi.jaws.transport.http.HttpServer;
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
 * End-to-end test for the MCP Stateless Streamable HTTP endpoint
 * integrated into {@link HttpServer}.
 * <p>
 * Uses Java's built-in {@link HttpClient} to speak MCP protocol against
 * a real {@link HttpServer}, verifying:
 * tools/list → tools/call → ping.
 */
class McpTransportTest {

    private HttpServer server;
    private int port;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        port = findFreePort();
        URL url = new URL("jaws", "127.0.0.1", port, "");

        ProviderMessageHandler messageHandler = new ProviderMessageHandler();
        server = new HttpServer(url, messageHandler);

        // Register a simple "hello" tool
        server.getMcpToolRegistry().register(new McpToolRegistry.Tool(
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
    void toolsListReturnsRegisteredTools() throws Exception {
        String body = jsonRpcRequest("tools/list", 1, Map.of());
        HttpResponse<String> response = sendPost(body);

        assertEquals(200, response.statusCode());
        JSONObject json = JSON.parseObject(response.body());
        assertEquals(1, json.get("id"));

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
        String body = jsonRpcRequest("tools/call", 2, Map.of(
                "name", "hello",
                "arguments", Map.of("name", "Jaws")
        ));
        HttpResponse<String> response = sendPost(body);

        assertEquals(200, response.statusCode());
        JSONObject json = JSON.parseObject(response.body());
        assertEquals(2, json.get("id"));

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
        String body = jsonRpcRequest("tools/call", 3, Map.of(
                "name", "nonexistent",
                "arguments", Map.of()
        ));
        HttpResponse<String> response = sendPost(body);

        assertEquals(200, response.statusCode());
        JSONObject json = JSON.parseObject(response.body());

        // Tool not found returns a JSON-RPC error response
        JSONObject error = json.getJSONObject("error");
        assertNotNull(error, "Should return a JSON-RPC error for unknown tool");
        assertEquals(-32601, error.getIntValue("code"));
        assertTrue(error.getString("message").contains("Tool not found"));
    }

    @Test
    void pingReturnsEmptyResult() throws Exception {
        String body = jsonRpcRequest("ping", 4, null);
        HttpResponse<String> response = sendPost(body);

        assertEquals(200, response.statusCode());
        JSONObject json = JSON.parseObject(response.body());
        assertEquals(4, json.get("id"));
        assertNotNull(json.get("result"));
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

    @Test
    void getMethodNotAllowed() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }

    // ---- Helpers ----

    private HttpResponse<String> sendPost(String body) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
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

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
