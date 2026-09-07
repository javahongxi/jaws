package org.hongxi.jaws.transport.mcp;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractTransportFactory;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;

import java.util.Set;

/**
 * {@link org.hongxi.jaws.transport.TransportFactory} implementation providing
 * the MCP Streamable HTTP transport for Jaws.
 * <p>
 * Select this transport by setting {@code transportFactory=mcp} in the
 * URL parameters. The server exposes a single MCP endpoint (default {@code /mcp})
 * supporting JSON-RPC 2.0 over HTTP with SSE streaming.
 * <p>
 * This transport is <b>server-only</b>: it does not support outbound client
 * connections. Use {@code netty} or {@code http2} for consumer-side transport.
 *
 * @author shenhongxi
 */
@Extension("mcp")
public class McpTransportFactory extends AbstractTransportFactory {

    @Override
    public Set<String> supportedProtocols() {
        return Set.of("mcp");
    }

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new McpServer(url);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        throw new UnsupportedOperationException(
                "MCP HTTP transport is server-only; use 'netty' or 'http2' for client connections");
    }
}
