package org.hongxi.jaws.transport.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sharable Netty handler that guards the server against too many concurrent
 * connections. It tracks every accepted connection keyed by local-remote
 * socket address pairs, immediately closes connections beyond the configured
 * limit, and can close all tracked connections on server shutdown.
 * <p>
 * Placed first in the server pipeline so connections are accounted for
 * before any other handler processes them.
 */
@ChannelHandler.Sharable
public class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ConnectionLimitHandler.class);

    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    private final int maxChannels;

    public ConnectionLimitHandler(int maxChannels) {
        super();
        this.maxChannels = maxChannels;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        synchronized (this) {
            if (channels.size() >= maxChannels) {
                log.warn("connected channel size out of limit: limit={} current={}", maxChannels, channels.size());
                channel.close();
                return;
            }
            String channelKey = getChannelKey((InetSocketAddress) channel.localAddress(), (InetSocketAddress) channel.remoteAddress());
            channels.put(channelKey, channel);
        }
        ctx.fireChannelRegistered();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        String channelKey = getChannelKey((InetSocketAddress) channel.localAddress(), (InetSocketAddress) channel.remoteAddress());
        channels.remove(channelKey);
        ctx.fireChannelUnregistered();
    }

    private String getChannelKey(InetSocketAddress localAddress, InetSocketAddress remoteAddress) {
        String key = "";
        if (localAddress == null || localAddress.getAddress() == null) {
            key += "null-";
        } else {
            key += localAddress.getAddress().getHostAddress() + ":" + localAddress.getPort() + "-";
        }

        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            key += "null";
        } else {
            key += remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort();
        }
        return key;
    }

    /** Closes all connections currently tracked by this handler. */
    public void closeAll() {
        channels.forEach((k, v) -> {
            if (v != null) {
                try {
                    v.close();
                } catch (Exception e) {
                    log.error("close channel error, {}", k, e);
                }
            }
        });
    }
}
