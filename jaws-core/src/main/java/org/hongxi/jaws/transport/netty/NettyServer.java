package org.hongxi.jaws.transport.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.StandardThreadPoolExecutor;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractServer;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty-based {@link org.hongxi.jaws.transport.Server} implementation built on
 * boss/worker {@link io.netty.channel.EventLoopGroup} NIO transport. Each child
 * channel runs the pipeline channel_manager → IdleStateHandler → HeartbeatHandler
 * → {@link NettyDecoder} → {@link NettyChannelHandler}, the last of which
 * dispatches decoded requests to a dedicated business thread pool.
 * <p>
 * Supports graceful shutdown via {@link #stopAccept()} and
 * {@link #awaitInactiveRequests(long)}, which stop taking new connections
 * and wait for in-flight requests to drain.
 *
 * @see NettyServerChannelManager
 * <p>
 * Created by shenhongxi on 2020/6/27.
 */
public class NettyServer extends AbstractServer {
    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    // volatile: written under the instance lock in open(), read lock-free in stopAccept()
    private volatile Channel serverChannel;
    protected NettyServerChannelManager channelManager;
    private final MessageHandler messageHandler;
    private ThreadPoolExecutor threadPoolExecutor;

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicInteger rejectCounter = new AtomicInteger(0);

    public NettyServer(URL url, MessageHandler messageHandler) {
        super(url);
        this.messageHandler = messageHandler;
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            log.debug("server channel already open, url={}", url);
            return state.isAliveState();
        }

        if (bossGroup == null) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
        }

        log.info("server channel start open, url={}", url);
        int maxServerConnections = url.getIntParameter(UrlParam.Server.MAX_CONNECTIONS);
        channelManager = new NettyServerChannelManager(maxServerConnections);

        if (threadPoolExecutor == null || threadPoolExecutor.isShutdown()) {
            threadPoolExecutor = new StandardThreadPoolExecutor(
                    url.getIntParameter(UrlParam.Server.MIN_WORKER_THREADS),
                    url.getIntParameter(UrlParam.Server.MAX_WORKER_THREADS),
                    url.getIntParameter(UrlParam.Server.WORKER_QUEUE_SIZE),
                    new DefaultThreadFactory("NettyServer-" + url.getHostPort(), true));
        }
        threadPoolExecutor.prestartAllCoreThreads();
        NettyChannelHandler channelHandler = new NettyChannelHandler(
                NettyServer.this, messageHandler, threadPoolExecutor);

        int maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("channel_manager", channelManager);
                        long heartbeat = url.getLongParameter(UrlParam.Transport.HEARTBEAT);
                        if (heartbeat > 0) {
                            pipeline.addLast("idle_state",
                                    new IdleStateHandler(heartbeat * 3, heartbeat, 0, TimeUnit.MILLISECONDS));
                            pipeline.addLast("heartbeat", new HeartbeatHandler(codec));
                        }
                        pipeline.addLast("decoder", new NettyDecoder(codec, NettyServer.this, maxContentLength));
                        pipeline.addLast("handler", channelHandler);
                    }
                });
        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        ChannelFuture channelFuture = serverBootstrap.bind(new InetSocketAddress(url.getPort()));
        channelFuture.syncUninterruptibly();
        serverChannel = channelFuture.channel();
        state = ChannelState.ALIVE;
        log.info("server channel finished open: url={}", url);
        return state.isAliveState();
    }

    @Override
    public synchronized void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        if (state.isCloseState()) return;

        try {
            cleanup();
            if (state.isUnInitState()) {
                log.info("Server close failed, state={}, uri={}", state.value(), url.getUri());
                return;
            }

            state = ChannelState.CLOSE;
            log.info("Server close success, uri={}", url.getUri());
        } catch (Exception e) {
            log.error("Server close error, uri={}", url.getUri(), e);
        }
    }

    private void cleanup() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (channelManager != null) {
            channelManager.close();
        }
        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdownNow();
        }
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }

    @Override
    public void stopAccept() {
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
            log.info("Server stopAccept: no longer accepting new connections, uri={}", url.getUri());
        }
    }

    public AtomicInteger getActiveRequests() {
        return activeRequests;
    }

    public AtomicInteger getRejectCounter() {
        return rejectCounter;
    }

    /**
     * Wait for in-flight requests to complete within the given timeout.
     */
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

    @Override
    public URL getUrl() {
        return url;
    }
}