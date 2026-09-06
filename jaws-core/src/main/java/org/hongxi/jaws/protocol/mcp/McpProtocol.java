package org.hongxi.jaws.protocol.mcp;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.protocol.AbstractProtocol;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;

/**
 * MCP (Model Context Protocol) protocol implementation, registered under the
 * {@code "mcp"} extension name.
 * <p>
 * This protocol exposes Jaws service methods as MCP tools through the
 * Streamable HTTP transport, enabling AI agents (Cursor, Claude Desktop,
 * MCP Inspector, etc.) to invoke Jaws services directly.
 * <p>
 * Server-only: does not support consumer-side {@code refer()}.
 *
 * @see McpExporter
 * @see org.hongxi.jaws.transport.mcp.McpServer
 */
@Extension("mcp")
public class McpProtocol extends AbstractProtocol {

    @Override
    protected <T> Exporter<T> createExporter(Provider<T> provider) {
        return new McpExporter<>(provider, provider.getUrl());
    }

    @Override
    protected <T> Reference<T> createReference(Class<T> interfaceClass, URL url) {
        throw new UnsupportedOperationException(
                "MCP protocol is server-only; use 'jaws' protocol for consumer-side references");
    }
}
