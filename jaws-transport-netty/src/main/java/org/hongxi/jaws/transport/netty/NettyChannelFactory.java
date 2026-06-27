package org.hongxi.jaws.transport.netty;

import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.StandardThreadPoolExecutor;
import org.hongxi.jaws.transport.Channel;
import org.hongxi.jaws.transport.SharedObjectFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by shenhongxi on 2020/7/30.
 */
public class NettyChannelFactory implements SharedObjectFactory<Channel> {
    private static final Logger log = LoggerFactory.getLogger(NettyChannelFactory.class);

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
        ReentrantLock lock = nettyChannel.getLock();
        if (lock.tryLock()) {
            try {
                if (!nettyChannel.isAvailable() && !nettyChannel.isReconnect()) {
                    nettyChannel.reconnect();
                    if (async) {
                        rebuildExecutorService.submit(new RebuildTask(nettyChannel));
                    } else {
                        nettyChannel.close();
                        nettyChannel.open();
                        log.info("rebuild channel success: {}", nettyChannel.getUrl());
                    }
                }
            } catch (Exception e) {
                log.error("rebuild error: {}, {}", this.toString(), nettyChannel.getUrl(), e);
            } finally {
                lock.unlock();
            }
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return factoryName;
    }

    class RebuildTask implements Runnable {
        private NettyChannel channel;

        public RebuildTask(NettyChannel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            try {
                channel.getLock().lock();
                channel.close();
                channel.open();
                log.info("rebuild channel success: {}", channel.getUrl());
            } catch (Exception e) {
                log.error("rebuild error: {}, {}", this.toString(), channel.getUrl(), e);
            } finally {
                channel.getLock().unlock();
            }
        }
    }
}
