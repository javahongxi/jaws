package org.hongxi.jaws.transport.netty;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractNettyServer;
import org.hongxi.jaws.transport.Codec;
import org.hongxi.jaws.transport.MessageHandler;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty-based {@link org.hongxi.jaws.transport.Server} implementation speaking
 * the jaws binary protocol. Each child channel runs the pipeline
 * IdleStateHandler → HeartbeatHandler → {@link NettyDecoder}
 * → {@link NettyChannelHandler}, the last of which dispatches decoded requests
 * to the business thread pool owned by {@link AbstractNettyServer}.
 * <p>
 * The business pool rejects with an error response when full (see
 * {@link NettyChannelHandler}), and graceful shutdown is driven by the base
 * class via {@link #stopAccept()} and {@link #awaitInactiveRequests(long)}.
 * <p>
 * Created by shenhongxi on 2020/6/27.
 */
public class NettyServer extends AbstractNettyServer {

    private final Codec codec;
    private final MessageHandler messageHandler;
    private final int maxContentLength;

    private final AtomicInteger rejectCounter = new AtomicInteger(0);

    // Created in onOpen() once the business pool exists
    private NettyChannelHandler channelHandler;

    public NettyServer(URL url, MessageHandler messageHandler) {
        super(url, "NettyServer");
        this.messageHandler = messageHandler;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class)
                .getExtension(url.getParameter(UrlParam.Transport.CODEC));
        this.maxContentLength = url.getIntParameter(UrlParam.Transport.MAX_CONTENT_LENGTH);
    }

    @Override
    protected void onOpen() {
        channelHandler = new NettyChannelHandler(this, messageHandler, serverExecutor);
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) {
        ChannelPipeline pipeline = socketChannel.pipeline();
        long heartbeat = url.getLongParameter(UrlParam.Transport.HEARTBEAT);
        if (heartbeat > 0) {
            pipeline.addLast("idle_state",
                    new IdleStateHandler(heartbeat * 3, heartbeat, 0, TimeUnit.MILLISECONDS));
            pipeline.addLast("heartbeat", new HeartbeatHandler(codec));
        }
        pipeline.addLast("decoder", new NettyDecoder(codec, this, maxContentLength));
        pipeline.addLast("handler", channelHandler);
    }

    public AtomicInteger getRejectCounter() {
        return rejectCounter;
    }
}
