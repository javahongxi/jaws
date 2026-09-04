package org.hongxi.jaws.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractClient;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Netty-based {@link Client} implementation maintaining a single native
 * Netty connection per remote URL. Installs the client pipeline
 * (IdleStateHandler → HeartbeatHandler → {@link NettyDecoder} →
 * {@link NettyChannelHandler}), encodes requests with zero-copy
 * {@link ByteBuf} allocation, and completes async requests through
 * {@link ResponseFuture} callbacks, each guarded by a one-shot timeout
 * scheduled on a shared HashedWheelTimer.
 * <p>
 * Supports error fusing: once consecutive errors reach the fusing threshold
 * the client is marked unavailable and recovers on success. Per-request
 * timeouts are resolved from dynamic configuration with a method → service
 * → global → URL fallback chain.
 * <p>
 * Created by shenhongxi on 2020/7/28.
 */
public class NettyClient extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(NettyClient.class);

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();

    private final Codec codec;
    private final InetSocketAddress remoteAddress;

    // volatile: written under the instance lock in open(), read by
    // business threads without locking in request()/isAvailable()
    private volatile io.netty.channel.Channel channel;
    private volatile InetSocketAddress localAddress;

    public NettyClient(URL url) {
        super(url);
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class)
                .getExtension(url.getParameter(UrlParam.Transport.CODEC));
        this.remoteAddress = new InetSocketAddress(url.getHost(), url.getPort());
        log.info("init netty client. url: {}-{}, use codec: {}",
                url.getHost(), url.getPath(), codec.getClass().getSimpleName());
    }

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("NettyClient is unavailable: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);
        if (timeout <= 0) {
            throw new JawsFrameworkException(
                    "NettyClient request failed: request timeout must be positive but was " + timeout);
        }

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout);
        registerCallback(request.getRequestId(), responseFuture);

        // Snapshot the volatile channel reference for this request
        io.netty.channel.Channel ch = channel;
        ByteBuf buf = null;
        try {
            buf = ch.alloc().buffer();
            codec.encode(this, request, buf);
        } catch (Exception e) {
            if (buf != null) {
                buf.release();
            }
            removeCallback(request.getRequestId());
            throw new JawsServiceException("encode request error: url=" + url.getUri(), e);
        }

        ChannelFuture writeFuture = ch.writeAndFlush(buf);
        boolean completed = writeFuture.awaitUninterruptibly(timeout, TimeUnit.MILLISECONDS);
        if (completed && writeFuture.isSuccess()) {
            responseFuture.whenComplete((r, t) -> {
                if (t == null || ExceptionUtils.isBizException(t)) {
                    resetErrorCount();
                } else {
                    incrErrorCount();
                }
            });
            return responseFuture;
        }

        writeFuture.cancel(true);
        responseFuture = (DefaultResponseFuture) removeCallback(request.getRequestId());
        if (responseFuture != null) {
            responseFuture.cancel();
        }
        incrErrorCount();

        if (writeFuture.cause() != null) {
            throw new JawsServiceException("NettyClient failed to send request to server: url="
                    + url.getUri() + " local=" + localAddress + " "
                    + RpcUtils.toString(request), writeFuture.cause());
        } else {
            throw new JawsServiceException("NettyClient timed out sending request to server: url="
                    + url.getUri() + " local=" + localAddress + " "
                    + RpcUtils.toString(request));
        }
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException("NettyClient init failed: connect timeout must be positive but was " + timeout);
        }

        Bootstrap bootstrap = new Bootstrap()
                .group(nioEventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        NettyClient.this.initChannel(ch);
                    }
                })
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true);

        // Connect to remote server
        ChannelFuture channelFuture = null;
        try {
            long start = System.currentTimeMillis();
            channelFuture = bootstrap.connect(remoteAddress);
            boolean completed = channelFuture.awaitUninterruptibly(timeout, TimeUnit.MILLISECONDS);
            boolean success = channelFuture.isSuccess();

            if (completed && success) {
                channel = channelFuture.channel();
                if (channel.localAddress() instanceof InetSocketAddress inetAddr) {
                    localAddress = inetAddr;
                }
                state = ChannelState.ALIVE;
                log.info("NettyClient opened successfully: url={}", url);
                return true;
            }

            channelFuture.cancel(true);
            if (channelFuture.cause() != null) {
                throw new JawsServiceException(
                        "NettyClient failed to connect to server, url: " + url.getUri() +
                                ", completed: " + completed + ", success: " + success,
                        channelFuture.cause());
            } else {
                throw new JawsServiceException(
                        "NettyClient connect to server timeout, url: " + url.getUri() +
                        ", cost: " + (System.currentTimeMillis() - start) +
                        ", completed: " + completed + ", success: " + success);
            }
        } catch (JawsServiceException e) {
            throw e;
        } catch (Exception e) {
            if (channelFuture != null) {
                channelFuture.channel().close();
            }
            throw new JawsServiceException("NettyClient failed to connect to server, url: " +
                    url.getUri(), e);
        } finally {
            if (channel == null) {
                incrErrorCount();
            }
        }
    }

    private void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        long heartbeat = url.getLongParameter(UrlParam.Transport.HEARTBEAT);
        if (heartbeat > 0) {
            pipeline.addLast("idle_state",
                    new IdleStateHandler(heartbeat * 3, heartbeat, 0, TimeUnit.MILLISECONDS));
            pipeline.addLast("heartbeat", new HeartbeatHandler(codec));
        }
        int maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
        pipeline.addLast("decoder", new NettyDecoder(NettyClient.this, codec, maxContentLength));
        pipeline.addLast("handler", new NettyChannelHandler(NettyClient.this, codec, (Object message) -> {
            Response response = (Response) message;
            // removeCallback: atomically claim + clean up the map entry,
            // so the timeout timer won't attempt a duplicate completion
            ResponseFuture responseFuture = NettyClient.this.removeCallback(response.getRequestId());

            if (responseFuture == null) {
                log.warn("received response from server, but no responseFuture found, requestId={}",
                        response.getRequestId());
                return CompletableFuture.completedFuture(null);
            }
            if (response.getThrowable() != null) {
                responseFuture.onFailure(response);
            } else {
                responseFuture.onSuccess(response);
            }
            return CompletableFuture.completedFuture(null);
        }));
    }

    @Override
    protected void doClose() {
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                log.error("failed to close netty channel: {} local={}", url.getUri(), localAddress, e);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return super.isAvailable() && channel != null && channel.isActive();
    }

    /**
     * Resolve request timeout from dynamic configuration with fallback chain:
     * method-level key -> service-level key -> global key -> URL default.
     */
    private int resolveTimeout(Request request, int urlDefault) {
        String interfaceName = request.getInterfaceName();
        String methodName = request.getMethodName();
        // Only positive values are accepted as valid timeouts
        return DynamicConfigurationUtils.resolveIntConfig(urlDefault, v -> v > 0,
                DynamicConfigurationKeys.requestTimeout(interfaceName, methodName),
                DynamicConfigurationKeys.requestTimeout(interfaceName),
                DynamicConfigurationKeys.GLOBAL_REQUEST_TIMEOUT);
    }
}
