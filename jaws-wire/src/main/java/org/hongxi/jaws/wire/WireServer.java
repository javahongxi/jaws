package org.hongxi.jaws.wire;

import io.netty.channel.ChannelPipeline;
import org.hongxi.jaws.common.UrlParam;
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
 *   <li>Keepalive PING policy: answers client PINGs (via Netty's auto-ACK) and
 *       guards against overly frequent PINGs with GOAWAY too_many_pings,
 *       per gRPC gRFC A8 {@code PERMIT_KEEPALIVE_TIME} semantics</li>
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

    /** Max size of a single inbound gRPC message in bytes. */
    private final int maxMessageSize;
    /** Configured response compression encoding (identity or gzip). */
    private final String compression;

    /**
     * Direct API mode: use a {@link WireServiceRegistry} for path-based routing
     * to typed {@link WireMethodHandler} instances.
     */
    public WireServer(URL url, WireServiceRegistry registry) {
        super(url, "WireServer");
        this.registry = registry;
        this.messageHandler = null;
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        this.compression = normalizeCompression(url);
    }

    /**
     * SPI adapter mode: use a Jaws {@link MessageHandler} pipeline, bridged
     * via {@link WireSpiServerStreamHandler}.
     */
    public WireServer(URL url, MessageHandler messageHandler) {
        super(url, "WireServer");
        this.registry = null;
        this.messageHandler = messageHandler;
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        this.compression = normalizeCompression(url);
    }

    private static String normalizeCompression(URL url) {
        String configured = url.getParameter(UrlParam.Transport.COMPRESSION);
        return configured != null && WireCompression.isSupported(configured)
                ? configured : WireConstants.ENCODING_IDENTITY;
    }

    @Override
    protected void addConnectionHandler(ChannelPipeline pipeline) {
        // gRPC keepalive guard: permit client PINGs no faster than
        // permitPingIntervalMs (default 5min, same as grpc-java); faster PINGs
        // get GOAWAY too_many_pings. Set 0 to permit all.
        long permitMs = url.getLongParameter(UrlParam.Transport.PERMIT_PING_INTERVAL_MS);
        pipeline.addLast("wire_keepalive", new WireKeepaliveHandler(permitMs));
    }

    @Override
    protected void initStreamChannel(io.netty.channel.Channel streamChannel) {
        if (registry != null) {
            streamChannel.pipeline().addLast(
                    new WireServerStreamHandler(registry, serverExecutor, maxMessageSize, compression));
        } else {
            streamChannel.pipeline().addLast(
                    new WireSpiServerStreamHandler(messageHandler, serverExecutor, maxMessageSize, compression));
        }
    }
}
