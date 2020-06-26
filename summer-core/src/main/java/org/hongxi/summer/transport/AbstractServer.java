package org.hongxi.summer.transport;

import org.hongxi.summer.codec.Codec;
import org.hongxi.summer.common.ChannelState;
import org.hongxi.summer.rpc.URL;

import java.net.InetSocketAddress;

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

    }
}
