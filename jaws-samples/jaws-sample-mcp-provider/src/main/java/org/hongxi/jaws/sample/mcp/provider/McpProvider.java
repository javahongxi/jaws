package org.hongxi.jaws.sample.mcp.provider;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.mcp.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.mcp.provider.service.OrderServiceImpl;

/**
 * MCP Streamable HTTP provider — uses {@code protocol=mcp} to expose Jaws
 * service interfaces as MCP tools, directly accessible from AI agents
 * (Cursor, Claude Desktop, MCP Inspector, etc.).
 * <p>
 * Each public interface method is auto-registered as an MCP tool with a
 * JSON Schema generated from the method signature. No manual tool
 * registration or annotation is needed.
 *
 * <pre>
 * Demo scenario:
 * 1. mcp protocol, no registry (standalone MCP server)
 * 2. DemoService + OrderService auto-registered as MCP tools
 * 3. Accessible via MCP Streamable HTTP on a single endpoint
 * </pre>
 *
 * <p>After startup, test with:
 * <pre>
 * # 1. Initialize handshake
 * curl -s -X POST http://localhost:18080/mcp \
 *   -H "Content-Type: application/json" \
 *   -H "Accept: application/json, text/event-stream" \
 *   -d '{"jsonrpc":"2.0","method":"initialize","id":1,"params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
 *   -D -
 *
 * # 2. List available tools (use Mcp-Session-Id from step 1)
 * curl -s -X POST http://localhost:18080/mcp \
 *   -H "Content-Type: application/json" \
 *   -H "Accept: application/json, text/event-stream" \
 *   -H "Mcp-Session-Id: &lt;session-id&gt;" \
 *   -d '{"jsonrpc":"2.0","method":"tools/list","id":2,"params":{}}'
 *
 * # 3. Call a tool
 * curl -s -X POST http://localhost:18080/mcp \
 *   -H "Content-Type: application/json" \
 *   -H "Accept: application/json, text/event-stream" \
 *   -H "Mcp-Session-Id: &lt;session-id&gt;" \
 *   -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"hello","arguments":{"name":"Jaws"}}}'
 * </pre>
 */
public class McpProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));

    public static void main(String[] args) {
        // MCP protocol — auto-registers interface methods as MCP tools
        ProtocolConfig mcpProtocol = new ProtocolConfig();
        mcpProtocol.setName("mcp");
        mcpProtocol.setId("mcp");
        mcpProtocol.setPort(PORT);

        // Export DemoService — each method becomes an MCP tool
        ServiceConfig<DemoService> demoService = new ServiceConfig<>();
        demoService.setRef(new DemoServiceImpl());
        demoService.setApplication("sample-mcp-provider");
        demoService.setModule("sample-mcp");
        demoService.setInterface(DemoService.class);
        demoService.setProtocol(mcpProtocol);
        demoService.export();

        // Export OrderService — same MCP server, same port
        ServiceConfig<OrderService> orderService = new ServiceConfig<>();
        orderService.setRef(new OrderServiceImpl());
        orderService.setApplication("sample-mcp-provider");
        orderService.setModule("sample-mcp");
        orderService.setInterface(OrderService.class);
        orderService.setProtocol(mcpProtocol);
        orderService.export();

        System.out.println("MCP Streamable HTTP provider started.");
        System.out.println("Endpoint: http://localhost:" + PORT + "/mcp");
        System.out.println();
        System.out.println("Auto-registered MCP tools:");
        System.out.println("  DemoService:");
        System.out.println("    hello(name)              — Say hello");
        System.out.println("    getUsers()               — List all users");
        System.out.println("    helloAsync(name)         — Async hello");
        System.out.println("    getUserAsync(name)       — Async get user");
        System.out.println("  OrderService:");
        System.out.println("    createOrder(...)         — Create an order");
        System.out.println("    getOrder(orderId)        — Get order by ID");
        System.out.println("    countOrders()            — Count all orders");
        System.out.println("    cancelOrder(orderId)     — Cancel an order");
        System.out.println();
        System.out.println("Note: overloaded methods (save) are skipped due to MCP name-only routing.");
        System.out.println();
        System.out.println("Test with MCP Inspector: npx @modelcontextprotocol/inspector");
        System.out.println("  URL: http://localhost:" + PORT + "/mcp");
    }
}
