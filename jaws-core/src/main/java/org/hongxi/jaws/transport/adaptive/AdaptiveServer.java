package org.hongxi.jaws.transport.adaptive;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractNettyServer;
import org.hongxi.jaws.transport.Codec;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.http.HttpRequestHandler;
import org.hongxi.jaws.transport.http2.Http2StreamServerHandler;
import org.hongxi.jaws.transport.netty.HeartbeatHandler;
import org.hongxi.jaws.transport.netty.NettyChannelHandler;
import org.hongxi.jaws.transport.netty.NettyDecoder;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Multi-protocol server that auto-detects the transport protocol from the
 * first bytes of each inbound TCP connection and configures the Netty
 * pipeline accordingly. A single {@code AdaptiveServer} instance can
 * simultaneously serve:
 * <ul>
 *   <li><b>Jaws binary protocol</b> (TCP, magic 0x4A57) — via
 *       {@link NettyDecoder} + {@link NettyChannelHandler}</li>
 *   <li><b>HTTP/2 h2c</b> (prior-knowledge, no TLS) — via
 *       {@link Http2FrameCodec} + {@link Http2MultiplexHandler} with
 *       Jaws-serialization stream handling</li>
 *   <li><b>HTTP/1.1</b> — via {@link HttpServerCodec} + aggregator +
 *       {@link HttpRequestHandler} (JSON RPC endpoint)</li>
 * </ul>
 * <p>
 * Detection is performed by {@link ProtocolDetectionHandler}, which buffers
 * the first bytes, inserts the correct pipeline, replays the buffered data,
 * and removes itself — leaving zero overhead after detection.
 * <p>
 * Extends {@link AbstractNettyServer} to reuse the bind skeleton, business
 * thread pool, graceful shutdown, and lifecycle management.
 *
 * @author shenhongxi
 * @see ProtocolDetectionHandler
 * @see AdaptiveTransportFactory
 */
public class AdaptiveServer extends AbstractNettyServer {

    private final MessageHandler messageHandler;
    private final Codec codec;
    private final int maxContentLength;
    private final String serializationName;
    private final long heartbeat;

    /** Interface classes registered for HTTP/1.1 JSON argument type conversion. */
    private final ConcurrentMap<String, Class<?>> interfaceClasses = new ConcurrentHashMap<>();

    public AdaptiveServer(URL url, MessageHandler messageHandler) {
        super(url, "AdaptiveServer");
        this.messageHandler = messageHandler;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class)
                .getExtension(url.getParameter(UrlParam.Transport.CODEC));
        this.maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
        this.serializationName = url.getParameter(UrlParam.Transport.SERIALIZATION);
        this.heartbeat = url.getLongParameter(UrlParam.Transport.HEARTBEAT);
    }

    /**
     * Register an interface class for HTTP/1.1 JSON argument type conversion.
     * When registered, the HTTP handler can convert JSON arguments to the
     * correct Java types based on the target method's parameter types.
     *
     * @param interfaceName  the fully-qualified interface name
     * @param interfaceClass the interface class
     */
    public void addInterfaceClass(String interfaceName, Class<?> interfaceClass) {
        interfaceClasses.put(interfaceName, interfaceClass);
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        // The detection handler sits at the front of the pipeline, buffering
        // bytes until the protocol can be determined. It then inserts the
        // protocol-specific handlers and removes itself.
        ch.pipeline().addLast("detector", new ProtocolDetectionHandler(this));
    }

    // ========================================================================
    // Pipeline builders — called by ProtocolDetectionHandler after detection
    // ========================================================================

    /**
     * Build the jaws binary protocol pipeline:
     * optional IdleStateHandler + HeartbeatHandler → NettyDecoder → NettyChannelHandler.
     */
    void addJawsBinaryPipeline(ChannelPipeline pipeline) {
        if (heartbeat > 0) {
            pipeline.addLast("idle_state", new IdleStateHandler(
                    heartbeat * 3, heartbeat, 0, TimeUnit.MILLISECONDS));
            pipeline.addLast("heartbeat", new HeartbeatHandler(codec));
        }
        pipeline.addLast("decoder", new NettyDecoder(this, codec, maxContentLength));
        pipeline.addLast("handler", new NettyChannelHandler(
                this, codec, messageHandler, serverExecutor, inflightRequests));
    }

    /**
     * Build the HTTP/2 pipeline: Http2FrameCodec → Http2MultiplexHandler
     * with {@link Http2StreamServerHandler} for each inbound stream.
     */
    void addHttp2Pipeline(ChannelPipeline pipeline) {
        pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forServer().build());
        pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(io.netty.channel.Channel streamChannel) {
                        streamChannel.pipeline().addLast(new Http2StreamServerHandler(
                                messageHandler, serverExecutor, serializationName,
                                inflightRequests, maxContentLength));
                    }
                }));
    }

    /**
     * Build the HTTP/1.1 pipeline: HttpServerCodec → HttpObjectAggregator → HttpRequestHandler.
     */
    void addHttp1Pipeline(ChannelPipeline pipeline) {
        pipeline.addLast("http_codec", new HttpServerCodec());
        pipeline.addLast("aggregator", new HttpObjectAggregator(maxContentLength));
        pipeline.addLast("http_handler", new HttpRequestHandler(
                messageHandler, serverExecutor, interfaceClasses));
    }
}
