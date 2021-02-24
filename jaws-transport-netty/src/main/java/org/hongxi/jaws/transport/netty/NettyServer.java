package org.hongxi.jaws.transport.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.StandardThreadPoolExecutor;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractServer;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public class NettyServer extends AbstractServer {
    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    protected NettyServerChannelManage channelManage;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private MessageHandler messageHandler;
    private ThreadPoolExecutor threadPoolExecutor;

    private AtomicInteger rejectCounter = new AtomicInteger(0);

    public AtomicInteger getRejectCounter() {
        return rejectCounter;
    }

    public NettyServer(URL url, MessageHandler messageHandler) {
        super(url);
        this.messageHandler = messageHandler;
    }

    @Override
    public boolean isBound() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    public Response request(Request request) throws TransportException {
        throw new JawsFrameworkException("NettyServer request(Request) method not support, url: " + url);
    }

    @Override
    public boolean open() {
        if (isAvailable()) {
            logger.warn("server channel already open, url={}", url);
            return state.isAliveState();
        }

        if (bossGroup == null) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
        }

        logger.info("server channel start open, url={}", url);
        boolean shareChannel = url.getBooleanParameter(
                URLParamType.shareChannel.getName(), URLParamType.shareChannel.boolValue());
        int maxContentLength = url.getIntParameter(
                URLParamType.maxContentLength.getName(), URLParamType.maxContentLength.intValue());
        int maxServerConnections = url.getIntParameter(
                URLParamType.maxServerConnections.getName(), URLParamType.maxServerConnections.intValue());
        int maxQueueSize = url.getIntParameter(
                URLParamType.workerQueueSize.getName(), URLParamType.workerQueueSize.intValue());

        int minWorkerThreads;
        int maxWorkerThreads;
        if (shareChannel) {
            minWorkerThreads = url.getIntParameter(URLParamType.minWorkerThreads.getName(),
                    JawsConstants.NETTY_SHARE_CHANNEL_MIN_WORKER_THREADS);
            maxWorkerThreads = url.getIntParameter(URLParamType.maxWorkerThreads.getName(),
                    JawsConstants.NETTY_SHARE_CHANNEL_MAX_WORKER_THREADS);
        } else {
            minWorkerThreads = url.getIntParameter(URLParamType.minWorkerThreads.getName(),
                    JawsConstants.NETTY_NOT_SHARE_CHANNEL_MIN_WORKER_THREADS);
            maxWorkerThreads = url.getIntParameter(URLParamType.maxWorkerThreads.getName(),
                    JawsConstants.NETTY_NOT_SHARE_CHANNEL_MAX_WORKER_THREADS);
        }

        if (threadPoolExecutor == null || threadPoolExecutor.isShutdown()) {
            threadPoolExecutor = new StandardThreadPoolExecutor(minWorkerThreads, maxWorkerThreads,
                    maxQueueSize, new DefaultThreadFactory("NettyServer-" + url.getServerPortStr(), true));
        }
        threadPoolExecutor.prestartAllCoreThreads();

        channelManage = new NettyServerChannelManage(maxServerConnections);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast("channel_manage", channelManage);
                        pipeline.addLast("decoder", new NettyDecoder(codec, NettyServer.this, maxContentLength));
                        pipeline.addLast("encoder", new NettyEncoder());
                        pipeline.addLast("handler", new NettyChannelHandler(NettyServer.this, messageHandler, threadPoolExecutor));
                    }
                });
        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        ChannelFuture channelFuture = serverBootstrap.bind(new InetSocketAddress(url.getPort()));
        channelFuture.syncUninterruptibly();
        serverChannel = channelFuture.channel();
        state = ChannelState.ALIVE;
        logger.info("server channel finished open: url={}", url);
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
                logger.info("Server close failed, state={}, uri={}", state.value(), url.getUri());
                return;
            }

            state = ChannelState.CLOSE;
            logger.info("Server close success, uri={}", url.getUri());
        } catch (Exception e) {
            logger.error("Server close error, uri={}", url.getUri(), e);
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
        if (channelManage != null) {
            channelManage.close();
        }
        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdownNow();
        }
    }

    @Override
    public boolean isClosed() {
        return state.isCloseState();
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }

    @Override
    public URL getUrl() {
        return url;
    }
}
