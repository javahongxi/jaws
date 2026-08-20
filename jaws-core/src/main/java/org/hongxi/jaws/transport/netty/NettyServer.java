package org.hongxi.jaws.transport.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.StandardThreadPoolExecutor;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractServer;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public class NettyServer extends AbstractServer {
    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
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
    public boolean isBound() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    public Response request(Request request) {
        throw new JawsFrameworkException("NettyServer request(Request) method not support, url: " + url);
    }

    @Override
    public boolean open() {
        if (isAvailable()) {
            log.warn("server channel already open, url={}", url);
            return state.isAliveState();
        }

        if (bossGroup == null) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
        }

        log.info("server channel start open, url={}", url);
        int maxServerConnections = url.getIntParameter(URLParamType.maxServerConnections);
        channelManager = new NettyServerChannelManager(maxServerConnections);

        if (threadPoolExecutor == null || threadPoolExecutor.isShutdown()) {
            threadPoolExecutor = new StandardThreadPoolExecutor(
                    url.getIntParameter(URLParamType.minWorkerThreads),
                    url.getIntParameter(URLParamType.maxWorkerThreads),
                    url.getIntParameter(URLParamType.workerQueueSize),
                    new DefaultThreadFactory("NettyServer-" + url.getHostPort(), true));
        }
        threadPoolExecutor.prestartAllCoreThreads();
        NettyChannelHandler channelHandler = new NettyChannelHandler(
                NettyServer.this, messageHandler, threadPoolExecutor);

        int maxContentLength = url.getIntParameter(URLParamType.maxContentLength);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("channel_manager", channelManager);
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

    @Override
    public int getActiveRequestCount() {
        return activeRequests.get();
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