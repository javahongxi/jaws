package org.hongxi.jaws.wire;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.EagerThreadPoolExecutor;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractServer;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gRPC server implementation based on Netty HTTP/2, independent of jaws-core's
 * {@code Http2Server}. Implements the full gRPC wire format:
 * <ul>
 *   <li>5-byte length-prefixed message framing</li>
 *   <li>{@code /{service}/{method}} path routing</li>
 *   <li>Trailers-based status reporting (grpc-status / grpc-message)</li>
 * </ul>
 * <p>
 * Each inbound HTTP/2 stream is handled by {@link WireServerStreamHandler},
 * which decodes the protobuf request, dispatches to the registered
 * {@link WireMethodHandler} on a business thread pool, and writes the
 * protobuf response as a gRPC frame.
 * <p>
 * This server speaks plain h2c (HTTP/2 prior-knowledge) without TLS.
 *
 * @author shenhongxi
 */
public class WireServer extends AbstractServer {
    private static final Logger log = LoggerFactory.getLogger(WireServer.class);

    private final WireServiceRegistry registry;
    private final MessageHandler messageHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile io.netty.channel.Channel serverChannel;
    private ThreadPoolExecutor serverExecutor;

    private final AtomicInteger activeRequests = new AtomicInteger(0);

    /** Tracks all active connection channels for GOAWAY on graceful shutdown. */
    private final io.netty.channel.group.ChannelGroup connectionChannels =
            new io.netty.channel.group.DefaultChannelGroup(
                    "wire-connections", GlobalEventExecutor.INSTANCE);

    /**
     * Direct API mode: use a {@link WireServiceRegistry} for path-based routing
     * to typed {@link WireMethodHandler} instances.
     */
    public WireServer(URL url, WireServiceRegistry registry) {
        super(url);
        this.registry = registry;
        this.messageHandler = null;
    }

    /**
     * SPI adapter mode: use a Jaws {@link MessageHandler} pipeline, bridged
     * via {@link WireSpiServerStreamHandler}.
     */
    public WireServer(URL url, MessageHandler messageHandler) {
        super(url);
        this.registry = null;
        this.messageHandler = messageHandler;
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            log.debug("Wire server already open, url={}", url);
            return true;
        }

        // Business pool: same pattern as Http2Server
        serverExecutor = new EagerThreadPoolExecutor(
                url.getIntParameter(UrlParam.Server.MIN_WORKER_THREADS),
                url.getIntParameter(UrlParam.Server.MAX_WORKER_THREADS),
                EagerThreadPoolExecutor.DEFAULT_MAX_IDLE_TIME, TimeUnit.MILLISECONDS,
                url.getIntParameter(UrlParam.Server.WORKER_QUEUE_SIZE),
                new DefaultThreadFactory("WireServer-" + url.getHostPort(), true),
                (command, pool) -> {
                    log.error("Wire server thread pool full, running task on transport thread: "
                                    + "active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={}",
                            pool.getActiveCount(), pool.getPoolSize(),
                            pool.getCorePoolSize(), pool.getMaximumPoolSize(), pool.getTaskCount());
                    command.run();
                });
        serverExecutor.prestartAllCoreThreads();

        // Non-daemon event loops keep the JVM alive after main() exits
        bossGroup = new NioEventLoopGroup(1,
                new DefaultThreadFactory("WireServerBoss-" + url.getHostPort(), false));
        workerGroup = new NioEventLoopGroup(0,
                new DefaultThreadFactory("WireServerWorker-" + url.getHostPort(), false));

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forServer().build());
                            pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                    new ChannelInitializer<io.netty.channel.Channel>() {
                                        @Override
                                        protected void initChannel(io.netty.channel.Channel streamChannel) {
                                            if (registry != null) {
                                                streamChannel.pipeline().addLast(
                                                        new WireServerStreamHandler(registry, serverExecutor));
                                            } else {
                                                streamChannel.pipeline().addLast(
                                                        new WireSpiServerStreamHandler(messageHandler, WireServer.this, serverExecutor));
                                            }
                                        }
                                    }));

                            connectionChannels.add(ch);
                        }
                    });
            bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture channelFuture = bootstrap.bind(new InetSocketAddress(url.getPort()));
            channelFuture.syncUninterruptibly();
            serverChannel = channelFuture.channel();
            state = ChannelState.ALIVE;

            log.info("Wire server started on port {}: url={}", url.getPort(), url);
        } catch (Exception e) {
            cleanup();
            throw new RuntimeException("Failed to start Wire server on port " + url.getPort(), e);
        }

        return true;
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
            if (serverExecutor != null) {
                serverExecutor.shutdown();
                serverExecutor = null;
            }
            state = ChannelState.CLOSE;
            log.info("Wire server closed: url={}", url);
        } catch (Exception e) {
            log.error("Wire server close error: url={}", url, e);
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
            serverChannel.close();
            log.info("Wire server stopAccept: no longer accepting new connections, url={}", url);
        }

        // Send GOAWAY to all active connections
        if (!connectionChannels.isEmpty()) {
            int count = 0;
            for (io.netty.channel.Channel conn : connectionChannels) {
                if (conn.isActive()) {
                    conn.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR));
                    count++;
                }
            }
            log.info("Wire server sent GOAWAY to {} active connections, url={}", count, url);
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
