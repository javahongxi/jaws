package org.hongxi.jaws.transport;

import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Created by shenhongxi on 2020/6/25.
 */
public abstract class AbstractServer implements Server {
    protected InetSocketAddress localAddress;
    protected InetSocketAddress remoteAddress;

    protected URL url;
    protected Codec codec;

    protected volatile ChannelState state = ChannelState.UNINIT;

    public AbstractServer() {}

    public AbstractServer(URL url) {
        this.url = url;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                url.getParameter(URLParamType.codec.getName(), URLParamType.codec.value()));
    }

    @Override
    public Collection<Channel> getChannels() {
        throw new JawsFrameworkException(this.getClass().getName() + " getChannels() method not support " + url);
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        throw new JawsFrameworkException(this.getClass().getName() + " getChannels(InetSocketAddress) method not support " + url);
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setLocalAddress(InetSocketAddress localAddress) {
        this.localAddress = localAddress;
    }

    public void setRemoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public void setUrl(URL url) {
        this.url = url;
    }

    public void setCodec(Codec codec) {
        this.codec = codec;
    }
}
