package org.hongxi.jaws.transport.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractClient;
import org.hongxi.jaws.transport.ChannelState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for HTTP/2 based clients, owning everything that is protocol
 * independent: the Netty bootstrap ({@code Http2FrameCodec} +
 * {@code Http2MultiplexHandler}), optional TLS with ALPN, multi-connection
 * support with round-robin selection, lazy reconnection, and lifecycle state.
 * <p>
 * Subclasses implement {@link #request(org.hongxi.jaws.rpc.Request)} with their
 * own request encoding and response handling — this is where the protocol
 * semantics live (jaws payload encoding for {@link Http2Client}, gRPC wire
 * format for the wire module's client). The connection pipeline assembled here is:
 * <pre>
 *   [ssl] → http2_codec → http2_multiplex
 * </pre>
 * <p>
 * Speaks plain h2c (HTTP/2 prior-knowledge) by default; TLS is enabled when
 * {@code sslTrustCert} (one-way) or {@code sslCertChain} + {@code sslPrivateKey}
 * (mutual) is configured on the URL. Multi-connection is enabled via the
 * {@code connections} URL parameter to distribute load across backends behind
 * L4 load balancers.
 * <p>
 * Idle-connection liveness relies on HTTP/2 PING frames managed by Netty's
 * {@code Http2FrameCodec} (keepalive) instead of application-level heartbeats.
 *
 * @author shenhongxi
 */
public abstract class AbstractHttp2Client extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(AbstractHttp2Client.class);

    /** Shared event loop group for all HTTP/2 client connections. */
    private static final NioEventLoopGroup NIO_EVENT_LOOP = new NioEventLoopGroup();

    /** Human-readable client name used in log messages and thread names. */
    private final String clientName;

    private final int connectionCount;
    private final SslContext sslContext;

    private Bootstrap bootstrap;

    /**
     * Multiple connections for L4 LB distribution. When connectionCount > 1,
     * requests are distributed across connections via round-robin.
     */
    private volatile io.netty.channel.Channel[] channels;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    protected AbstractHttp2Client(URL url, String clientName) {
        super(url);
        this.clientName = clientName;
        this.connectionCount = Math.max(1, url.getIntParameter(UrlParam.Transport.CONNECTIONS));
        this.sslContext = buildSslContext();
    }

    /**
     * @return the SSL context, or null if TLS is not configured
     */
    protected SslContext getSslContext() {
        return sslContext;
    }

    /**
     * Build an {@link SslContext} for TLS if trust cert or mutual TLS is configured.
     * Uses ALPN to negotiate HTTP/2.
     *
     * @return the SslContext, or null if TLS is not configured
     */
    private SslContext buildSslContext() {
        String trustCert = url.getParameter(UrlParam.Transport.SSL_TRUST_CERT);
        String certChain = url.getParameter(UrlParam.Transport.SSL_CERT_CHAIN);
        String privateKey = url.getParameter(UrlParam.Transport.SSL_PRIVATE_KEY);

        boolean hasTrust = trustCert != null && !trustCert.isEmpty();
        boolean hasMutualTls = certChain != null && !certChain.isEmpty()
                && privateKey != null && !privateKey.isEmpty();

        if (!hasTrust && !hasMutualTls) {
            return null;
        }

        try {
            SslContextBuilder builder;
            if (hasMutualTls) {
                builder = SslContextBuilder.forClient()
                        .keyManager(new File(certChain), new File(privateKey));
            } else {
                builder = SslContextBuilder.forClient();
            }
            if (hasTrust) {
                builder.trustManager(new File(trustCert));
            }
            return builder
                    .sslProvider(SslProvider.JDK)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build client SSL context", e);
        }
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException(clientName
                    + " init failed: connect timeout must be positive but was " + timeout);
        }

        bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.group(eventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // Add TLS if configured (before HTTP/2 codec)
                        if (sslContext != null) {
                            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(),
                                    url.getHost(), url.getPort()));
                        }
                        // HTTP/2 framing & flow control; liveness relies on TCP keepalive
                        // and HTTP/2 PINGs instead of application-level heartbeats
                        pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forClient().build());
                        pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                new io.netty.channel.ChannelInboundHandlerAdapter()));
                    }
                });

        // Open multiple connections if configured
        channels = new io.netty.channel.Channel[connectionCount];
        for (int i = 0; i < connectionCount; i++) {
            doConnect(i);
        }
        state = ChannelState.ALIVE;

        String tlsInfo = sslContext != null ? " with TLS" : "";
        String connInfo = connectionCount > 1 ? " (" + connectionCount + " connections)" : "";
        log.info("{} opened successfully{}{}: url={}", clientName, tlsInfo, connInfo, url);
        return true;
    }

    private void doConnect(int index) {
        ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort()).syncUninterruptibly();
        if (!future.isSuccess()) {
            throw new JawsServiceException(clientName + " connect failed: url=" + url.getUri(),
                    future.cause());
        }
        channels[index] = future.channel();
    }

    /**
     * Select a connection channel using round-robin for multi-connection setups.
     */
    protected io.netty.channel.Channel selectConnection() {
        if (channels == null || channels.length == 0) {
            throw new JawsServiceException("No HTTP/2 connections available: url=" + url.getUri());
        }
        if (channels.length == 1) {
            return channels[0];
        }
        int idx = Math.abs(requestCounter.getAndIncrement() % channels.length);
        return channels[idx];
    }

    /**
     * Return an active connection, lazily reconnecting if the selected
     * connection has gone away.
     */
    protected io.netty.channel.Channel activeConnection() {
        io.netty.channel.Channel connChannel = selectConnection();
        if (!connChannel.isActive()) {
            reconnect();
            connChannel = selectConnection();
        }
        if (!connChannel.isActive()) {
            throw new JawsServiceException(clientName + " channel is not active: url=" + url.getUri());
        }
        return connChannel;
    }

    /**
     * Re-establish all connections. Called lazily from request paths when a
     * multiplexed connection has gone away.
     */
    synchronized void reconnect() {
        if (state.isCloseState()) {
            return;
        }
        if (channels != null) {
            boolean allActive = true;
            for (io.netty.channel.Channel ch : channels) {
                if (ch == null || !ch.isActive()) {
                    allActive = false;
                    break;
                }
            }
            if (allActive) {
                return;
            }
        }
        // Close inactive channels and reconnect
        if (channels != null) {
            for (int i = 0; i < channels.length; i++) {
                if (channels[i] != null && !channels[i].isActive()) {
                    channels[i].close();
                    channels[i] = null;
                }
                if (channels[i] == null) {
                    try {
                        doConnect(i);
                        log.info("{} reconnected connection[{}]: url={}", clientName, i, url.getUri());
                    } catch (Exception e) {
                        log.error("{} reconnect failed for connection[{}]: url={}",
                                clientName, i, url.getUri(), e);
                    }
                }
            }
        }
    }

    /**
     * @return the shared event loop group used by this client class
     */
    protected EventLoopGroup eventLoopGroup() {
        return NIO_EVENT_LOOP;
    }

    @Override
    protected void doClose() {
        if (channels != null) {
            for (io.netty.channel.Channel ch : channels) {
                if (ch != null) {
                    ch.close();
                }
            }
            channels = null;
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        if (channels == null) {
            return false;
        }
        // At least one connection must be open
        for (io.netty.channel.Channel ch : channels) {
            if (ch != null && ch.isOpen()) {
                return true;
            }
        }
        return false;
    }
}
