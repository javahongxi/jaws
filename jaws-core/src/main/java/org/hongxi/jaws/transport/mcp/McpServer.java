package org.hongxi.jaws.transport.mcp;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractNettyServer;

/**
 * Lightweight HTTP/1.1 server implementing the MCP (Model Context Protocol)
 * Streamable HTTP transport.
 * <p>
 * The Netty pipeline is:
 * <pre>
 *   http_codec → aggregator → mcp_handler
 * </pre>
 * <p>
 * Unlike {@link org.hongxi.jaws.transport.http.HttpServer} which exposes
 * {@code POST /invoke} for Jaws RPC calls, this server speaks the MCP
 * protocol on a single endpoint (default {@code /mcp}), supporting:
 * <ul>
 *   <li>JSON-RPC 2.0 message exchange over HTTP POST</li>
 *   <li>SSE (Server-Sent Events) listening stream over HTTP GET</li>
 *   <li>Session lifecycle via {@code Mcp-Session-Id} header</li>
 *   <li>Tool registration for AI agent invocation</li>
 * </ul>
 * <p>
 * This enables Jaws services to be directly accessible from MCP clients
 * (Cursor, Claude Desktop, MCP Inspector, etc.) without any SDK dependency.
 *
 * @author shenhongxi
 * @see McpHandler
 * @see McpToolRegistry
 */
public class McpServer extends AbstractNettyServer {

    private final McpSessionManager sessionManager;
    private final McpToolRegistry toolRegistry;
    private final int maxContentLength;
    private final String mcpEndpoint;

    public McpServer(URL url) {
        super(url, "McpServer");
        this.sessionManager = new McpSessionManager();
        this.toolRegistry = new McpToolRegistry();
        this.maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
        this.mcpEndpoint = url.getParameter("mcpEndpoint", "/mcp");
    }

    /**
     * @return the tool registry for registering MCP tools
     */
    public McpToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * @return the session manager for monitoring active sessions
     */
    public McpSessionManager getSessionManager() {
        return sessionManager;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("http_codec", new HttpServerCodec());
        pipeline.addLast("aggregator", new HttpObjectAggregator(maxContentLength));
        pipeline.addLast("mcp_handler", new McpHandler(
                mcpEndpoint, sessionManager, toolRegistry, serverExecutor));
    }

    @Override
    protected void closeConnections() {
        sessionManager.closeAll();
    }
}
