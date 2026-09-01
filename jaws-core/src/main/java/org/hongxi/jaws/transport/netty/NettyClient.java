package org.hongxi.jaws.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractClient;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Netty-based {@link Client} implementation maintaining a single
 * {@link NettyChannel} connection per remote URL. Installs the client
 * pipeline (IdleStateHandler → HeartbeatHandler → {@link NettyDecoder} →
 * {@link NettyChannelHandler}) and completes async requests through
 * {@link ResponseFuture} callbacks, each guarded by a one-shot timeout
 * scheduled on a shared HashedWheelTimer.
 * <p>
 * Also implements error fusing: once consecutive errors reach the fusing
 * threshold the client is marked unavailable and recovers on success.
 *
 * @see NettyChannel
 * <p>
 * Created by shenhongxi on 2020/7/28.
 */
public class NettyClient extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(NettyClient.class);

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();

    private final Codec codec;

    private Bootstrap bootstrap;
    // volatile: written under the instance lock in open()/close(), read lock-free in request()
    private volatile NettyChannel channel;

    public NettyClient(URL url) {
        super(url);
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class)
                .getExtension(url.getParameter(UrlParam.Transport.CODEC));
        log.info("init netty client. url: {}-{}, use codec: {}",
                url.getHost(), url.getPath(), codec.getClass().getSimpleName());
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("NettyChannel is unavailable: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        boolean async = false;
        Object asyncFlag = RpcContext.getContext().getAttribute(JawsConstants.ASYNC_FLAG);
        if (asyncFlag instanceof Boolean b) {
            async = b;
        }

        Response response;
        try {
            if (!channel.isAvailable()) {
                channel.reconnect();
            }
            if (!channel.isAvailable()) {
                throw new JawsServiceException("NettyChannel is not available: url="
                        + url.getUri() + RpcUtils.toString(request));
            }
            response = channel.request(request);
        } catch (Exception e) {
            log.error("request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());

            if (e instanceof JawsAbstractException jae) {
                throw jae;
            } else {
                throw new JawsServiceException("NettyClient request failed: url=" +
                        url.getUri() + " " + RpcUtils.toString(request), e);
            }
        }

        return async ? response : new DefaultResponse(response);
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = getUrl().getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException("NettyClient init failed: connect timeout must be positive but was " + timeout);
        }

        final int maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);

        bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.group(nioEventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        long heartbeat = url.getLongParameter(UrlParam.Transport.HEARTBEAT);
                        if (heartbeat > 0) {
                            pipeline.addLast("idle_state",
                                    new IdleStateHandler(heartbeat * 3, heartbeat, 0, TimeUnit.MILLISECONDS));
                            pipeline.addLast("heartbeat", new HeartbeatHandler(codec));
                        }
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
                });

        // Create single connection
        channel = new NettyChannel(this);
        channel.open();

        log.info("NettyClient opened successfully: url={}", url);

        // Set available state
        state = ChannelState.ALIVE;
        return true;
    }

    @Override
    protected void doClose() {
        // Close the channel
        if (channel != null) {
            channel.close();
        }
    }
}