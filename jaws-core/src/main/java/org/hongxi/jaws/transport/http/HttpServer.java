package org.hongxi.jaws.transport.http;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractNettyServer;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.http.rest.RestMappingRegistry;
import org.hongxi.jaws.transport.http.mcp.McpToolRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight HTTP/1.1 server that exposes:
 * <ul>
 *   <li>{@code POST /invoke} — JSON RPC invocation</li>
 *   <li>{@code POST /mcp} — MCP (Model Context Protocol) Stateless Streamable HTTP</li>
 *   <li>{@code GET /health} — health check probe</li>
 * </ul>
 * <p>
 * The Netty pipeline is:
 * <pre>
 *   [idle_state] → http_codec → aggregator → request_handler
 * </pre>
 * {@code idle_state} is only installed when {@code maxConnectionIdleMs} is
 * positive; it reaps keep-alive connections that go idle, so the pool depth of
 * callers bounds their footprint.
 * <p>
 * Unlike {@link org.hongxi.jaws.transport.http2.Http2Server} which speaks
 * HTTP/2 with Jaws binary serialization, this server speaks HTTP/1.1 with
 * JSON, making it accessible from {@code curl}, browsers, and any standard
 * HTTP client without special protocol handling.
 * <p>
 * Interface classes can be registered via
 * {@link #addInterfaceClass(String, Class)} to enable automatic JSON-to-type
 * argument conversion based on method parameter types.
 *
 * @author shenhongxi
 */
public class HttpServer extends AbstractNettyServer {

    private final MessageHandler messageHandler;
    private final int maxContentLength;

    /** Reap keep-alive connections idle longer than this; 0 disables (default). */
    private final long maxConnectionIdleMs;

    private final ConcurrentMap<String, Class<?>> interfaceClasses = new ConcurrentHashMap<>();
    private final RestMappingRegistry restMappingRegistry = new RestMappingRegistry();
    private final McpToolRegistry mcpToolRegistry = new McpToolRegistry();

    public HttpServer(URL url, MessageHandler messageHandler) {
        super(url, "HttpServer");
        this.messageHandler = messageHandler;
        this.maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
        this.maxConnectionIdleMs = url.getLongParameter(UrlParam.Server.MAX_CONNECTION_IDLE_MS);
    }

    /**
     * Register an interface class for JSON argument type conversion.
     * When registered, the HTTP handler can convert JSON arguments to the
     * correct Java types based on the target method's parameter types.
     *
     * @param interfaceName the fully-qualified interface name
     * @param interfaceClass the interface class
     */
    public void addInterfaceClass(String interfaceName, Class<?> interfaceClass) {
        interfaceClasses.put(interfaceName, interfaceClass);
    }

    /**
     * @return the REST mapping registry for annotation-driven route registration.
     *         Lazily initialized; always non-null.
     */
    public RestMappingRegistry getRestMappingRegistry() {
        return restMappingRegistry;
    }

    /**
     * @return the MCP tool registry for registering MCP tools
     */
    public McpToolRegistry getMcpToolRegistry() {
        return mcpToolRegistry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        if (maxConnectionIdleMs > 0) {
            pipeline.addLast("idle_state", new IdleStateHandler(0, 0, maxConnectionIdleMs, TimeUnit.MILLISECONDS));
        }
        pipeline.addLast("http_codec", new HttpServerCodec());
        pipeline.addLast("aggregator", new HttpObjectAggregator(maxContentLength));
        pipeline.addLast("http_handler", new HttpRequestHandler(
                messageHandler, serverExecutor, interfaceClasses, restMappingRegistry, mcpToolRegistry));
    }

    @Override
    protected void closeConnections() {
        // Individual keep-alive connections are reaped by the idle timeout
        // (maxConnectionIdleMs) when configured; graceful shutdown tears down
        // the event loops, which closes every channel either way.
    }
}
