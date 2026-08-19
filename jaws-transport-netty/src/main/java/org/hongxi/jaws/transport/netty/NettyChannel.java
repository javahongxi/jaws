package org.hongxi.jaws.transport.netty;

import io.netty.channel.ChannelFuture;
import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.codec.CodecUtils;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.config.configcenter.DynamicConfiguration;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.transport.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Created by shenhongxi on 2020/7/30.
 */
public class NettyChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(NettyChannel.class);

    private volatile ChannelState state = ChannelState.UNINIT;
    private final NettyClient nettyClient;
    private io.netty.channel.Channel channel = null;
    private final InetSocketAddress remoteAddress;
    private InetSocketAddress localAddress;
    private final Codec codec;

    public NettyChannel(NettyClient nettyClient) {
        this.nettyClient = nettyClient;
        this.remoteAddress = new InetSocketAddress(nettyClient.getUrl().getHost(), nettyClient.getUrl().getPort());
        codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                nettyClient.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.value()));
    }

    @Override
    public Response request(Request request) {
        // Resolve timeout with dynamic configuration priority:
        // method-level dynamic > service-level dynamic > global dynamic > URL config
        int urlTimeout = nettyClient.getUrl().getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                URLParamType.requestTimeout.getName(), URLParamType.requestTimeout.intValue());
        int timeout = resolveTimeout(request, urlTimeout);
        if (timeout <= 0) {
            throw new JawsFrameworkException(
                    "NettyClient init Error: timeout(" + timeout + ") <= 0 is forbid.",
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }
        ResponseFuture response = new DefaultResponseFuture(request, timeout, this.nettyClient.getUrl());
        this.nettyClient.registerCallback(request.getRequestId(), response);
        byte[] msg = CodecUtils.encodeObjectToBytes(this, codec, request);
        ChannelFuture writeFuture = this.channel.writeAndFlush(msg);
        boolean result = writeFuture.awaitUninterruptibly(timeout, TimeUnit.MILLISECONDS);

        if (result && writeFuture.isSuccess()) {
            response.addListener(new FutureListener() {
                @Override
                public void operationComplete(Future future) throws Exception {
                    if (future.isSuccess() || (future.isDone() && ExceptionUtils.isBizException(future.getException()))) {
                        // Successful invocation
                        nettyClient.resetErrorCount();
                    } else {
                        // Failed invocation
                        nettyClient.incrErrorCount();
                    }
                }
            });
            return response;
        }

        writeFuture.cancel(true);
        response = this.nettyClient.removeCallback(request.getRequestId());
        if (response != null) {
            response.cancel();
        }
        // Failed invocation
        nettyClient.incrErrorCount();

        if (writeFuture.cause() != null) {
            throw new JawsServiceException("NettyChannel send request to server Error: url="
                    + nettyClient.getUrl().getUri() + " local=" + localAddress + " "
                    + JawsFrameworkUtils.toString(request), writeFuture.cause());
        } else {
            throw new JawsServiceException("NettyChannel send request to server Timeout: url="
                    + nettyClient.getUrl().getUri() + " local=" + localAddress + " "
                    + JawsFrameworkUtils.toString(request));
        }
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            log.warn("the channel already open, local: {} remote: {} url: {}",
                    localAddress, remoteAddress, nettyClient.getUrl().getUri());
            return true;
        }

        int timeout = nettyClient.getUrl().getIntParameter(URLParamType.connectTimeout);
        if (timeout <= 0) {
            throw new JawsFrameworkException("NettyClient init Error: timeout(" + timeout + ") <= 0 is forbid.",
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }

        ChannelFuture channelFuture = null;
        try {
            long start = System.currentTimeMillis();
            channelFuture = nettyClient.getBootstrap().connect(remoteAddress);
            boolean completed = channelFuture.awaitUninterruptibly(timeout, TimeUnit.MILLISECONDS);
            boolean success = channelFuture.isSuccess();

            if (completed && success) {
                channel = channelFuture.channel();
                if (channel.localAddress() instanceof InetSocketAddress inetAddr) {
                    localAddress = inetAddr;
                }
                state = ChannelState.ALIVE;
                return true;
            }

            boolean connected = false;
            if (channelFuture.channel() != null) {
                connected = channelFuture.channel().isActive();
            }

            channelFuture.cancel(true);
            if (channelFuture.cause() != null) {
                throw new JawsServiceException(
                        "NettyChannel failed to connect to server, url: " +
                                nettyClient.getUrl().getUri() + ", completed: " + completed +
                                ", success: " + success +
                                ", connected: " + connected, channelFuture.cause());
            } else {
                throw new JawsServiceException("NettyChannel connect to server timeout, url: " +
                        nettyClient.getUrl().getUri() +
                        ", cost: " + (System.currentTimeMillis() - start) +
                        ", completed: " + completed +
                        ", success: " + success +
                        ", connected: " + connected);
            }
        } catch (JawsServiceException e) {
            throw e;
        } catch (Exception e) {
            if (channelFuture != null) {
                channelFuture.channel().close();
            }
            throw new JawsServiceException("NettyChannel failed to connect to server, url: " +
                    nettyClient.getUrl().getUri(), e);
        } finally {
            if (!state.isAliveState()) {
                nettyClient.incrErrorCount();
            }
        }
    }

    @Override
    public synchronized void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        try {
            state = ChannelState.CLOSE;

            if (channel != null) {
                channel.close();
            }
        } catch (Exception e) {
            log.error("channel close Error: {} local={}",
                    nettyClient.getUrl().getUri(), localAddress, e);
        }
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState() && channel != null && channel.isActive();
    }

    /**
     * Reconnect this channel by closing and re-opening the underlying connection.
     */
    public synchronized void reconnect() {
        try {
            close();
            open();
        } catch (Exception e) {
            log.error("reconnect error: url={}", getUrl(), e);
        }
    }

    @Override
    public URL getUrl() {
        return nettyClient.getUrl();
    }

    /**
     * Resolve request timeout from dynamic configuration with fallback chain:
     * method-level key -> service-level key -> global key -> URL default.
     */
    private int resolveTimeout(Request request, int urlDefault) {
        DynamicConfiguration dc = DynamicConfigurationUtils.getDynamicConfiguration();
        if (!dc.hasAnyConfig()) {
            return urlDefault;
        }
        String interfaceName = request.getInterfaceName();
        String methodName = request.getMethodName();

        // method-level dynamic override
        int val = dc.getIntConfig(DynamicConfigurationKeys.requestTimeout(interfaceName, methodName), 0);
        if (val > 0) {
            return val;
        }
        // service-level dynamic override
        val = dc.getIntConfig(DynamicConfigurationKeys.requestTimeout(interfaceName), 0);
        if (val > 0) {
            return val;
        }
        // global dynamic override
        val = dc.getIntConfig(DynamicConfigurationKeys.GLOBAL_REQUEST_TIMEOUT, 0);
        if (val > 0) {
            return val;
        }
        return urlDefault;
    }
}
