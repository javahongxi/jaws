package org.hongxi.jaws.transport.http;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractNettyServer;
import org.hongxi.jaws.transport.MessageHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lightweight HTTP/1.1 server that exposes a {@code POST /invoke} endpoint for
 * invoking Jaws RPC services with JSON payloads, plus a {@code GET /health}
 * probe for load-balancer health checks.
 * <p>
 * The Netty pipeline is:
 * <pre>
 *   http_codec → aggregator → request_handler
 * </pre>
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
    private final ConcurrentMap<String, Class<?>> interfaceClasses = new ConcurrentHashMap<>();

    public HttpServer(URL url, MessageHandler messageHandler) {
        super(url, "HttpServer");
        this.messageHandler = messageHandler;
        this.maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
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

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("http_codec", new HttpServerCodec());
        pipeline.addLast("aggregator", new HttpObjectAggregator(maxContentLength));
        pipeline.addLast("http_handler", new HttpRequestHandler(
                messageHandler, serverExecutor, interfaceClasses));
    }

    @Override
    protected void closeConnections() {
        // no persistent connection tracking needed for HTTP/1.1
    }
}
