package org.hongxi.jaws.transport.http2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.EagerThreadPoolExecutor;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractServer;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.transport.netty.ConnectionLimitHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for HTTP/2 based servers, owning everything that is protocol
 * independent: the Netty bootstrap skeleton ({@code Http2FrameCodec} +
 * {@code Http2MultiplexHandler}), the business thread pool, connection
 * tracking with GOAWAY on graceful shutdown, optional TLS with ALPN, and
 * the connection limiter.
 * <p>
 * Subclasses implement {@link #initStreamChannel(io.netty.channel.Channel)}
 * to install their per-stream handler — this is where the protocol semantics
 * live (jaws payload encoding for {@link Http2Server}, gRPC wire format for
 * the wire module's server). The pipeline assembled here is:
 * <pre>
 *   connection_limit → [ssl] → http2_codec → http2_multiplex → (per-stream handler)
 * </pre>
 * <p>
 * Speaking plain h2c (HTTP/2 prior-knowledge) by default; TLS is enabled when
 * {@code sslCertChain} and {@code sslPrivateKey} are configured on the URL.
 * <p>
 * Boss/worker event loop threads are created as non-daemon (via
 * {@link DefaultThreadFactory}) so the JVM stays alive after the main thread
 * exits.
 *
 * @author shenhongxi
 */
public abstract class AbstractHttp2Server extends AbstractServer {
    private static final Logger log = LoggerFactory.getLogger(AbstractHttp2Server.class);

    /** Human-readable server name used in log messages and thread names. */
    private final String serverName;

    protected EventLoopGroup bossGroup;
    protected EventLoopGroup workerGroup;
    // volatile: written under the instance lock in open(), read lock-free in stopAccept()
    protected volatile io.netty.channel.Channel serverChannel;
    protected ThreadPoolExecutor serverExecutor;

    /** Tracks in-flight business requests for graceful shutdown draining. */
    protected final AtomicInteger activeRequests = new AtomicInteger(0);

    /** Tracks all active connection channels for GOAWAY on graceful shutdown. */
    private final ChannelGroup connectionChannels;

    private ConnectionLimitHandler connectionLimiter;
    private SslContext sslContext;

    protected AbstractHttp2Server(URL url, String serverName) {
        super(url);
        this.serverName = serverName;
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

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            log.debug("{} server already open, url={}", serverName, url);
            return true;
        }

        int maxServerConnections = url.getIntParameter(UrlParam.Server.MAX_CONNECTIONS);
        connectionLimiter = new ConnectionLimitHandler(maxServerConnections);

        // Business pool aligned with NettyServer: bounded and config-driven
        // (minWorkerThreads/maxWorkerThreads/workerQueueSize).
        serverExecutor = new EagerThreadPoolExecutor(
                url.getIntParameter(UrlParam.Server.MIN_WORKER_THREADS),
                url.getIntParameter(UrlParam.Server.MAX_WORKER_THREADS),
                EagerThreadPoolExecutor.DEFAULT_MAX_IDLE_TIME, TimeUnit.MILLISECONDS,
                url.getIntParameter(UrlParam.Server.WORKER_QUEUE_SIZE),
                new DefaultThreadFactory(serverName + "-" + url.getHostPort(), true),
                (command, pool) -> {
                    // No access to the response stream here, so fall back to
                    // the transport thread instead of dropping the request.
                    log.error("{} server thread pool full, running task on transport thread: "
                                    + "active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={}",
                            serverName,
                            pool.getActiveCount(), pool.getPoolSize(),
                            pool.getCorePoolSize(), pool.getMaximumPoolSize(), pool.getTaskCount());
                    command.run();
                });
        serverExecutor.prestartAllCoreThreads();

        // Non-daemon event loops keep the JVM alive after main() exits,
        // mirroring NettyServer's behavior.
        bossGroup = new NioEventLoopGroup(1,
                new DefaultThreadFactory(serverName + "Boss-" + url.getHostPort(), false));
        workerGroup = new NioEventLoopGroup(0,
                new DefaultThreadFactory(serverName + "Worker-" + url.getHostPort(), false));

        // Initialize TLS if configured
        sslContext = buildSslContext();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast("connection_limit", connectionLimiter);

                            // Add TLS if configured
                            if (sslContext != null) {
                                pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                            }

                            pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forServer().build());
                            pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                    new ChannelInitializer<io.netty.channel.Channel>() {
                                        @Override
                                        protected void initChannel(io.netty.channel.Channel streamChannel) {
                                            initStreamChannel(streamChannel);
                                        }
                                    }));

                            // Track connection channel for GOAWAY on shutdown
                            connectionChannels.add(ch);
                        }
                    });
            bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture channelFuture = bootstrap.bind(new InetSocketAddress(url.getPort()));
            channelFuture.syncUninterruptibly();
            serverChannel = channelFuture.channel();
            state = ChannelState.ALIVE;

            String tlsInfo = sslContext != null ? " with TLS" : "";
            log.info("{} server started on port {}{}: url={}", serverName, url.getPort(), tlsInfo, url);
        } catch (Exception e) {
            cleanup();
            throw new RuntimeException("Failed to start " + serverName + " server on port " + url.getPort(), e);
        }

        return true;
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

    @Override
    public synchronized void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        if (state.isCloseState()) return;

        int waitMs = timeout > 0 ? timeout :
                url.getIntParameter(UrlParam.Server.GRACEFUL_SHUTDOWN_TIMEOUT);
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
                serverChannel = null;
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully(0, waitMs, TimeUnit.MILLISECONDS).syncUninterruptibly();
                bossGroup = null;
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully(0, waitMs, TimeUnit.MILLISECONDS).syncUninterruptibly();
                workerGroup = null;
            }
            if (connectionLimiter != null) {
                connectionLimiter.closeAll();
            }
            if (serverExecutor != null) {
                serverExecutor.shutdown();
                serverExecutor = null;
            }
            state = ChannelState.CLOSE;
            log.info("{} server closed: url={}", serverName, url);
        } catch (Exception e) {
            log.error("{} server close error: url={}", serverName, url, e);
            cleanup();
            state = ChannelState.CLOSE;
        }
    }

    private void cleanup() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
    }

    @Override
    public void stopAccept() {
        if (serverChannel != null && serverChannel.isOpen()) {
            // Close the listening socket only; existing connections and in-flight
            // streams are left untouched so they can drain naturally.
            serverChannel.close();
            log.info("{} server stopAccept: no longer accepting new connections, url={}", serverName, url);
        }

        // Send GOAWAY to all active connections so clients stop opening new
        // streams on this server and migrate to other backends.
        if (!connectionChannels.isEmpty()) {
            int count = 0;
            for (io.netty.channel.Channel conn : connectionChannels) {
                if (conn.isActive()) {
                    conn.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR));
                    count++;
                }
            }
            log.info("{} server sent GOAWAY to {} active connections, url={}", serverName, count, url);
        }
    }

    @Override
    public void awaitInactiveRequests(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (activeRequests.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = activeRequests.get();
        if (remaining > 0) {
            log.warn("Graceful shutdown timeout reached, {} requests still in-flight, uri={}",
                    remaining, url.getUri());
        } else {
            log.info("All in-flight requests completed before shutdown, uri={}", url.getUri());
        }
    }

    public AtomicInteger getActiveRequests() {
        return activeRequests;
    }
}
