package org.hongxi.jaws.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.EagerThreadPoolExecutor;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base implementation of {@link Server} for Netty-based servers, holding
 * the bound {@link URL}, the volatile {@link ChannelState} lifecycle flag,
 * and everything that is protocol independent: the boss/worker event loops,
 * the business thread pool, the {@code ServerBootstrap} bind skeleton,
 * graceful shutdown with draining of in-flight requests, and failure cleanup
 * on open/close errors.
 * <p>
 * Subclasses implement {@link #initChannel(SocketChannel)} to assemble the
 * per-connection pipeline — this is where the protocol semantics live
 * (jaws binary protocol for {@link org.hongxi.jaws.transport.netty.NettyServer},
 * {@code Http2FrameCodec} + multiplex for
 * {@link org.hongxi.jaws.transport.http2.AbstractHttp2Server}).
 * <p>
 * Boss/worker event loop threads are created as non-daemon (via
 * {@link DefaultThreadFactory}) so the JVM stays alive after the main thread
 * exits.
 *
 * @author shenhongxi
 */
public abstract class AbstractNettyServer implements Server {
    private static final Logger log = LoggerFactory.getLogger(AbstractNettyServer.class);

    protected final URL url;

    /** Human-readable server name used in log messages and thread names. */
    protected final String serverName;

    protected EventLoopGroup bossGroup;
    protected EventLoopGroup workerGroup;
    // volatile: written under the instance lock in open(), read lock-free in stopAccept()
    protected volatile io.netty.channel.Channel serverChannel;
    protected volatile ChannelState state = ChannelState.UNINIT;
    protected ThreadPoolExecutor serverExecutor;

    /** Tracks in-flight business requests for graceful shutdown draining. */
    protected final AtomicInteger activeRequests = new AtomicInteger(0);

    protected AbstractNettyServer(URL url, String serverName) {
        this.url = url;
        this.serverName = serverName;
    }

    /**
     * Assemble the per-connection pipeline for a newly accepted channel.
     *
     * @param ch the newly accepted connection channel
     */
    protected abstract void initChannel(SocketChannel ch) throws Exception;

    /**
     * Hook called inside {@link #open()} after the event loops and business
     * pool are ready but before the bind, for subclass-specific preparation
     * (e.g. building an SSL context or a sharable pipeline handler).
     * Failures thrown here trigger the same cleanup as a bind failure.
     */
    protected void onOpen() throws Exception {
        // no-op by default
    }

    /**
     * Rejection policy of the business thread pool. Default aborts so the
     * pipeline handler can answer with an error response; subclasses may
     * override, e.g. to fall back to the transport thread.
     */
    protected RejectedExecutionHandler newRejectedExecutionHandler() {
        return new ThreadPoolExecutor.AbortPolicy();
    }

    /**
     * Hook for extra steps in {@link #stopAccept()} after the listening
     * socket is closed, e.g. sending GOAWAY on existing HTTP/2 connections.
     */
    protected void stopAcceptExtra() {
        // no-op by default
    }

    /**
     * Hook to release protocol-specific connection resources during
     * {@link #close(int)} and failure cleanup, e.g. tracked connection
     * channels of an HTTP/2 server.
     */
    protected void closeConnections() {
        // no-op by default
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            log.debug("{} server already open, url={}", serverName, url);
            return true;
        }

        // Business pool aligned across transports: bounded and config-driven
        // (minWorkerThreads/maxWorkerThreads/workerQueueSize).
        serverExecutor = new EagerThreadPoolExecutor(
                url.getIntParameter(UrlParam.Server.MIN_WORKER_THREADS),
                url.getIntParameter(UrlParam.Server.MAX_WORKER_THREADS),
                EagerThreadPoolExecutor.DEFAULT_MAX_IDLE_TIME, TimeUnit.MILLISECONDS,
                url.getIntParameter(UrlParam.Server.WORKER_QUEUE_SIZE),
                new DefaultThreadFactory(serverName + "-" + url.getHostPort(), true),
                newRejectedExecutionHandler());
        serverExecutor.prestartAllCoreThreads();

        // Non-daemon event loops keep the JVM alive after main() exits.
        bossGroup = new NioEventLoopGroup(1,
                new DefaultThreadFactory(serverName + "Boss-" + url.getHostPort(), false));
        workerGroup = new NioEventLoopGroup(0,
                new DefaultThreadFactory(serverName + "Worker-" + url.getHostPort(), false));

        try {
            onOpen();

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            AbstractNettyServer.this.initChannel(ch);
                        }
                    });
            bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture channelFuture = bootstrap.bind(new InetSocketAddress(url.getPort()));
            channelFuture.syncUninterruptibly();
            serverChannel = channelFuture.channel();
            state = ChannelState.ALIVE;
            log.info("{} server started on port {}: url={}", serverName, url.getPort(), url);
        } catch (Exception e) {
            cleanup();
            throw new RuntimeException("Failed to start " + serverName + " server on port " + url.getPort(), e);
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
            closeConnections();
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
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        closeConnections();
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }

    @Override
    public void stopAccept() {
        if (serverChannel != null && serverChannel.isOpen()) {
            // Close the listening socket only; existing connections and
            // in-flight requests are left untouched so they can drain naturally.
            serverChannel.close();
            log.info("{} server stopAccept: no longer accepting new connections, url={}", serverName, url);
        }
        stopAcceptExtra();
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
