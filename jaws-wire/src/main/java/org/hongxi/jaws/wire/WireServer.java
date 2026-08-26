package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.http2.AbstractHttp2Server;

/**
 * gRPC server implementation based on Netty HTTP/2, independent of jaws-core's
 * {@code Http2Server} at the protocol layer. Implements the full gRPC wire format:
 * <ul>
 *   <li>5-byte length-prefixed message framing</li>
 *   <li>{@code /{service}/{method}} path routing</li>
 *   <li>Trailers-based status reporting (grpc-status / grpc-message)</li>
 * </ul>
 * <p>
 * Each inbound HTTP/2 stream is handled by {@link WireServerStreamHandler}
 * (direct API mode) or {@link WireSpiServerStreamHandler} (SPI adapter mode),
 * which decodes the protobuf request, dispatches to the registered handler
 * on a business thread pool, and writes the protobuf response as a gRPC frame.
 * <p>
 * The Netty bootstrap skeleton, business thread pool, GOAWAY-based graceful
 * shutdown, optional TLS with ALPN (h2 over TLS, interoperable with standard
 * gRPC clients), and connection limiting are provided by
 * {@link AbstractHttp2Server}.
 *
 * @author shenhongxi
 */
public class WireServer extends AbstractHttp2Server {

    private final WireServiceRegistry registry;
    private final MessageHandler messageHandler;

    /**
     * Direct API mode: use a {@link WireServiceRegistry} for path-based routing
     * to typed {@link WireMethodHandler} instances.
     */
    public WireServer(URL url, WireServiceRegistry registry) {
        super(url, "WireServer");
        this.registry = registry;
        this.messageHandler = null;
    }

    /**
     * SPI adapter mode: use a Jaws {@link MessageHandler} pipeline, bridged
     * via {@link WireSpiServerStreamHandler}.
     */
    public WireServer(URL url, MessageHandler messageHandler) {
        super(url, "WireServer");
        this.registry = null;
        this.messageHandler = messageHandler;
    }

    @Override
    protected void initStreamChannel(io.netty.channel.Channel streamChannel) {
        if (registry != null) {
            streamChannel.pipeline().addLast(
                    new WireServerStreamHandler(registry, serverExecutor));
        } else {
            streamChannel.pipeline().addLast(
                    new WireSpiServerStreamHandler(messageHandler, this, serverExecutor));
        }
    }
}
