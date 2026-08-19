package org.hongxi.jaws.transport.netty;

import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.StandardThreadPoolExecutor;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.SharedObjectFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by shenhongxi on 2020/7/30.
 */
public class NettyChannelFactory implements SharedObjectFactory<Channel> {

    private static final ExecutorService rebuildExecutorService = new StandardThreadPoolExecutor(
            5, 30, 10L, TimeUnit.SECONDS, 100,
            new DefaultThreadFactory("RebuildExecutorService", true),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private NettyClient nettyClient;
    private String factoryName;

    public NettyChannelFactory(NettyClient nettyClient) {
        this.nettyClient = nettyClient;
        this.factoryName = "NettyChannelFactory_" + nettyClient.getUrl().getHost() +
                "_" + nettyClient.getUrl().getPort();
    }

    @Override
    public Channel makeObject() {
        return new NettyChannel(nettyClient);
    }

    @Override
    public boolean rebuildObject(Channel obj, boolean async) {
        NettyChannel nettyChannel = (NettyChannel) obj;
        return nettyChannel.reconnect(async, rebuildExecutorService);
    }

    @Override
    public String toString() {
        return factoryName;
    }
}
