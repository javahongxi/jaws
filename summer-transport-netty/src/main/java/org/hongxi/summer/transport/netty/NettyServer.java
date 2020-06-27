package org.hongxi.summer.transport.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.hongxi.summer.exception.SummerFrameworkException;
import org.hongxi.summer.rpc.Request;
import org.hongxi.summer.rpc.Response;
import org.hongxi.summer.rpc.URL;
import org.hongxi.summer.transport.AbstractServer;
import org.hongxi.summer.transport.MessageHandler;
import org.hongxi.summer.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public class NettyServer extends AbstractServer {
    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    protected NettyServerChannelManage channelManage;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private MessageHandler messageHandler;

    private AtomicInteger rejectCounter = new AtomicInteger(0);

    public AtomicInteger getRejectCounter() {
        return rejectCounter;
    }

    public NettyServer(URL url, MessageHandler messageHandler) {
        super(url);
        this.messageHandler = messageHandler;
    }

    @Override
    public boolean isBound() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    public Response request(Request request) throws TransportException {
        throw new SummerFrameworkException("NettyServer request(Request) method not support, url: " + url);
    }

    @Override
    public boolean open() {
        if (isAvailable()) {
            logger.warn("server channel already open, url: {}", url);
            return state.isAliveState();
        }

        if (bossGroup == null) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
        }

        logger.warn("server channel start open, url: {}", url);


        return state.isAliveState();
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

    @Override
    public URL getUrl() {
        return null;
    }
}