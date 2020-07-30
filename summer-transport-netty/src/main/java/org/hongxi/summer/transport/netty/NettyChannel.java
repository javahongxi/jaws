package org.hongxi.summer.transport.netty;

import org.hongxi.summer.codec.Codec;
import org.hongxi.summer.common.ChannelState;
import org.hongxi.summer.common.URLParamType;
import org.hongxi.summer.core.extension.ExtensionLoader;
import org.hongxi.summer.rpc.Request;
import org.hongxi.summer.rpc.Response;
import org.hongxi.summer.rpc.URL;
import org.hongxi.summer.transport.Channel;
import org.hongxi.summer.transport.TransportException;

import java.net.InetSocketAddress;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by shenhongxi on 2020/7/30.
 */
public class NettyChannel implements Channel {
    private volatile ChannelState state = ChannelState.UNINIT;
    private NettyClient nettyClient;
    private io.netty.channel.Channel channel = null;
    private InetSocketAddress remoteAddress = null;
    private InetSocketAddress localAddress = null;
    private ReentrantLock lock = new ReentrantLock();
    private Codec codec;

    public NettyChannel(NettyClient nettyClient) {
        this.nettyClient = nettyClient;
        this.remoteAddress = new InetSocketAddress(nettyClient.getUrl().getHost(), nettyClient.getUrl().getPort());
        codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                nettyClient.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.value()));
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public Response request(Request request) throws TransportException {
        return null;
    }

    @Override
    public boolean open() {
        return false;
    }

    @Override
    public void close() {

    }

    @Override
    public void close(int timeout) {

    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    public void reconnect() {
        state = ChannelState.INIT;
    }

    public boolean isReconnect() {
        return state.isInitState();
    }

    @Override
    public URL getUrl() {
        return null;
    }

    public ReentrantLock getLock() {
        return lock;
    }
}
