package org.hongxi.jaws.transport.http2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.EagerThreadPoolExecutor;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractServer;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.netty.ConnectionLimitHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP/2-based {@link org.hongxi.jaws.transport.Server} implementation built on
 * Netty's {@code Http2FrameCodec} + {@code Http2MultiplexHandler}, speaking plain
 * h2c (HTTP/2 prior-knowledge) without any gRPC/protobuf dependency.
 * <p>
 * Each inbound HTTP/2 stream is initialized with an {@link Http2ServerStreamHandler}
 * that reassembles the request DATA frames, dispatches to the Jaws
 * {@link MessageHandler} pipeline on the business thread pool, and writes the
 * serialized response back on the same stream.
 * <p>
 * Unlike {@code NettyServer}, this server does not use the Jaws binary protocol
 * or its {@link org.hongxi.jaws.transport.Codec}: HTTP/2 framing and flow control
 * are provided by Netty, while business payloads keep using the Jaws
 * {@link org.hongxi.jaws.serialization.Serialization} SPI.
 * <p>
 * Supports both unary and streaming invocations. Streaming methods (server/client/
 * bidirectional) are detected via the {@code x-jaws-streaming} header and dispatched
 * to the provider's {@code callStream()} method.
 * <p>
 * Boss/worker event loop threads are created as non-daemon (via
 * {@link DefaultThreadFactory}) so the JVM stays alive after the main thread
 * exits, playing the same anchor role the gRPC module used to rely on
 * pre-started pool threads for.
 *
 * @author shenhongxi
 */
public class Http2Server extends AbstractServer {
    private static final Logger log = LoggerFactory.getLogger(Http2Server.class);

    private final MessageHandler messageHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    // volatile: written under the instance lock in open(), read lock-free in stopAccept()
    private volatile io.netty.channel.Channel serverChannel;
    private ConnectionLimitHandler connectionLimiter;
    private ThreadPoolExecutor serverExecutor;

    private final AtomicInteger activeRequests = new AtomicInteger(0);

    /** Lightweight Channel facade passed to MessageHandler for server-side context. */
    private final Channel serverChannelFacade = new Channel() {
        @Override
        public boolean open() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(int timeout) {
        }

        @Override
        public boolean isAvailable() {
            return Http2Server.this.isAvailable();
        }

        @Override
        public URL getUrl() {
            return url;
        }
    };

    public Http2Server(URL url, MessageHandler messageHandler) {
        super(url);
        this.messageHandler = messageHandler;
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            log.debug("HTTP/2 server already open, url={}", url);
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
                new DefaultThreadFactory("Http2Server-" + url.getHostPort(), true),
                (command, pool) -> {
                    // No access to the response stream here, so fall back to
                    // the transport thread instead of dropping the request.
                    log.error("thread pool full, running task on transport thread: "
                                    + "active={} poolSize={} corePoolSize={} maxPoolSize={} taskCount={}",
                            pool.getActiveCount(), pool.getPoolSize(),
                            pool.getCorePoolSize(), pool.getMaximumPoolSize(), pool.getTaskCount());
                    command.run();
                });
        serverExecutor.prestartAllCoreThreads();

        // Non-daemon event loops keep the JVM alive after main() exits,
        // mirroring NettyServer's behavior.
        bossGroup = new NioEventLoopGroup(1,
                new DefaultThreadFactory("Http2ServerBoss-" + url.getHostPort(), false));
        workerGroup = new NioEventLoopGroup(0,
                new DefaultThreadFactory("Http2ServerWorker-" + url.getHostPort(), false));

        int maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
        String serializationName = url.getParameter(UrlParam.Transport.SERIALIZATION);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast("connection_limit", connectionLimiter);
                            pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forServer().build());
                            pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                    new ChannelInitializer<io.netty.channel.Channel>() {
                                        @Override
                                        protected void initChannel(io.netty.channel.Channel streamChannel) {
                                            streamChannel.pipeline().addLast(new Http2ServerStreamHandler(
                                                    messageHandler, serverChannelFacade, serverExecutor,
                                                    serializationName, activeRequests, maxContentLength));
                                        }
                                    }));
                        }
                    });
            bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture channelFuture = bootstrap.bind(new InetSocketAddress(url.getPort()));
            channelFuture.syncUninterruptibly();
            serverChannel = channelFuture.channel();
            state = ChannelState.ALIVE;
            log.info("HTTP/2 server started on port {}: url={}", url.getPort(), url);
        } catch (Exception e) {
            cleanup();
            throw new RuntimeException("Failed to start HTTP/2 server on port " + url.getPort(), e);
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
            if (connectionLimiter != null) {
                connectionLimiter.closeAll();
            }
            if (serverExecutor != null) {
                serverExecutor.shutdown();
                serverExecutor = null;
            }
            state = ChannelState.CLOSE;
            log.info("HTTP/2 server closed: url={}", url);
        } catch (Exception e) {
            log.error("HTTP/2 server close error: url={}", url, e);
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
            log.info("HTTP/2 server stopAccept: no longer accepting new connections, url={}", url);
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
