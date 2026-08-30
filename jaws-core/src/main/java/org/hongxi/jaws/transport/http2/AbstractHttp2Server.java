package org.hongxi.jaws.transport.http2;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractNettyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Base class for HTTP/2 based servers, adding the HTTP/2-specific parts on top
 * of {@link AbstractNettyServer}: the {@code Http2FrameCodec} +
 * {@code Http2MultiplexHandler} pipeline, connection tracking with GOAWAY on
 * graceful shutdown, and optional TLS with ALPN.
 * <p>
 * Subclasses implement {@link #initStreamChannel(io.netty.channel.Channel)}
 * to install their per-stream handler — this is where the protocol semantics
 * live (jaws payload encoding for {@link Http2Server}, gRPC wire format for
 * the wire module's server). The pipeline assembled here is:
 * <pre>
 *   [ssl] → http2_codec → http2_multiplex → (per-stream handler)
 * </pre>
 * <p>
 * Speaking plain h2c (HTTP/2 prior-knowledge) by default; TLS is enabled when
 * {@code sslCertChain} and {@code sslPrivateKey} are configured on the URL.
 *
 * @author shenhongxi
 */
public abstract class AbstractHttp2Server extends AbstractNettyServer {
    private static final Logger log = LoggerFactory.getLogger(AbstractHttp2Server.class);

    /** Tracks all active connection channels for GOAWAY on graceful shutdown. */
    private final ChannelGroup connectionChannels;

    private SslContext sslContext;

    protected AbstractHttp2Server(URL url, String serverName) {
        super(url, serverName);
        this.connectionChannels = new DefaultChannelGroup(
                serverName.toLowerCase() + "-connections", GlobalEventExecutor.INSTANCE);
    }

    /**
     * Install the protocol-specific handler for a newly opened HTTP/2 stream.
     * Called once per stream by the multiplex handler; the implementation
     * typically adds exactly one inbound handler to the stream channel
     * pipeline and hands business work to {@link #serverExecutor}.
     *
     * @param streamChannel the channel representing this HTTP/2 stream
     */
    protected abstract void initStreamChannel(io.netty.channel.Channel streamChannel);

    /**
     * Hook for connection-level (non-stream) handlers, invoked between
     * {@code http2_codec} and {@code http2_multiplex}. Default no-op;
     * subclasses may install e.g. a keepalive PING policy guard. Handlers
     * must use {@code acceptInboundMessage} filtering — stream frames still
     * pass through this point.
     *
     * @param pipeline the connection channel pipeline
     */
    protected void addConnectionHandler(ChannelPipeline pipeline) {
        // no-op by default
    }

    @Override
    protected void onOpen() {
        // Initialize TLS if configured
        sslContext = buildSslContext();
        if (sslContext != null) {
            log.info("{} server TLS enabled with ALPN h2: url={}", serverName, url);
        }
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // Add TLS if configured
        if (sslContext != null) {
            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
        }

        pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forServer().build());

        // Optional connection-level guard installed by the subclass
        // (e.g. gRPC keepalive PING permitting on the wire server)
        addConnectionHandler(pipeline);

        pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(io.netty.channel.Channel streamChannel) {
                        initStreamChannel(streamChannel);
                    }
                }));

        // Track connection channel for GOAWAY on shutdown
        connectionChannels.add(ch);
    }

    @Override
    protected void stopAcceptExtra() {
        // Send GOAWAY to all active connections so clients stop opening new
        // streams on this server and migrate to other backends.
        if (!connectionChannels.isEmpty()) {
            int count = 0;
            for (io.netty.channel.Channel ch : connectionChannels) {
                if (ch.isActive()) {
                    ch.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR));
                    count++;
                }
            }
            log.info("{} server sent GOAWAY to {} active connections, url={}", serverName, count, url);
        }
    }

    @Override
    protected void closeConnections() {
        connectionChannels.close();
    }

    /**
     * Build an {@link SslContext} for TLS if cert and key are configured.
     * Uses ALPN to negotiate HTTP/2.
     *
     * @return the SslContext, or null if TLS is not configured
     */
    private SslContext buildSslContext() {
        String certChain = url.getParameter(UrlParam.Transport.SSL_CERT_CHAIN);
        String privateKey = url.getParameter(UrlParam.Transport.SSL_PRIVATE_KEY);
        if (certChain == null || certChain.isEmpty() || privateKey == null || privateKey.isEmpty()) {
            return null;
        }
        try {
            return SslContextBuilder.forServer(new File(certChain), new File(privateKey))
                    .sslProvider(SslProvider.JDK)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build SSL context: certChain=" + certChain
                    + ", privateKey=" + privateKey, e);
        }
    }
}
