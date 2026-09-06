package org.hongxi.jaws.protocol.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.Protocol;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the MCP Protocol SPI integration.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>{@link McpProtocol} is loadable via {@link ExtensionLoader}</li>
 *   <li>Interface methods are auto-registered as MCP tools</li>
 *   <li>The full lifecycle (export → MCP call → unexport) works through the Protocol SPI</li>
 * </ul>
 */
class McpProtocolIntegrationTest {

    private Protocol mcpProtocol;
    private org.hongxi.jaws.rpc.Exporter<?> exporter;
    private int port;
    private HttpClient httpClient;

    // ---- Sample service interface and implementation ----

    interface GreetingService {
        String greet(String name);

        int add(int a, int b);
    }

    static class GreetingServiceImpl implements GreetingService {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Override
        public int add(int a, int b) {
            return a + b;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Load McpProtocol via SPI
        mcpProtocol = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension("mcp");
        assertNotNull(mcpProtocol, "McpProtocol must be loadable via ExtensionLoader");

        port = findFreePort();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (exporter != null) {
            exporter.destroy();
        }
    }

    @Test
    void mcpProtocolLoadableViaSpi() {
        assertInstanceOf(McpProtocol.class, mcpProtocol);
    }

    @Test
    void autoRegistersToolsFromInterface() throws Exception {
        // Export a service via McpProtocol
        URL url = new URL("mcp", "127.0.0.1", port, GreetingService.class.getName());
        url.addParameter("mcpEndpoint", "/mcp");
        DefaultProvider<GreetingService> provider = new DefaultProvider<>(
                GreetingService.class, url, new GreetingServiceImpl());

        exporter = mcpProtocol.export(provider);
        assertTrue(exporter.isAvailable());

        // Initialize MCP session
        String sessionId = initializeSession();

        // tools/list should show auto-registered methods
        JSONObject toolsResult = sendToolsList(sessionId);
        var tools = toolsResult.getJSONArray("tools");
        assertNotNull(tools);
        assertEquals(2, tools.size(), "Should have 2 tools: greet and add");

        // Verify tool names
        var toolNames = tools.stream()
                .map(t -> ((JSONObject) t).getString("name"))
                .sorted()
                .toList();
        assertTrue(toolNames.contains("add"));
        assertTrue(toolNames.contains("greet"));
    }

    @Test
    void toolCallInvokesActualMethod() throws Exception {
        URL url = new URL("mcp", "127.0.0.1", port, GreetingService.class.getName());
        url.addParameter("mcpEndpoint", "/mcp");
        DefaultProvider<GreetingService> provider = new DefaultProvider<>(
                GreetingService.class, url, new GreetingServiceImpl());

        exporter = mcpProtocol.export(provider);

        String sessionId = initializeSession();

        // Call greet("World")
        JSONObject greetResult = sendToolsCall(sessionId, "greet", Map.of("name", "World"));
        var content = greetResult.getJSONArray("content");
        assertNotNull(content);
        assertEquals(1, content.size());
        assertEquals("Hello, World!", content.getJSONObject(0).getString("text"));

        // Call add(3, 4)
        JSONObject addResult = sendToolsCall(sessionId, "add", Map.of("a", 3, "b", 4));
        var addContent = addResult.getJSONArray("content");
        assertNotNull(addContent);
        assertEquals("7", addContent.getJSONObject(0).getString("text"));
    }

    // ---- MCP protocol helpers ----

    private String initializeSession() throws Exception {
        String body = jsonRpcRequest("initialize", 1, Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1.0")
        ));
        HttpResponse<String> response = sendPost(body, null);
        assertEquals(200, response.statusCode());
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElseThrow();

        // Send initialized notification
        sendPost(jsonRpcNotification("notifications/initialized"), sessionId);
        return sessionId;
    }

    private JSONObject sendToolsList(String sessionId) throws Exception {
        String body = jsonRpcRequest("tools/list", 2, Map.of());
        HttpResponse<String> response = sendPost(body, sessionId);
        assertEquals(200, response.statusCode());
        String json = extractSseData(response.body());
        return JSON.parseObject(json).getJSONObject("result");
    }

    private JSONObject sendToolsCall(String sessionId, String toolName,
                                      Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        String body = jsonRpcRequest("tools/call", 3, params);
        HttpResponse<String> response = sendPost(body, sessionId);
        assertEquals(200, response.statusCode());
        String json = extractSseData(response.body());
        return JSON.parseObject(json).getJSONObject("result");
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

    // ---- JSON-RPC helpers ----

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

    private static String jsonRpcNotification(String method) {
        JSONObject obj = new JSONObject();
        obj.put("jsonrpc", "2.0");
        obj.put("method", method);
        return obj.toJSONString();
    }

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
