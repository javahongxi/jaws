package org.hongxi.summer.transport.netty;

import io.netty.channel.ChannelDuplexHandler;
import org.hongxi.summer.codec.Codec;
import org.hongxi.summer.common.URLParamType;
import org.hongxi.summer.common.extension.ExtensionLoader;
import org.hongxi.summer.transport.Channel;
import org.hongxi.summer.transport.MessageHandler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Created by shenhongxi on 2020/7/7.
 */
public class NettyChannelHandler extends ChannelDuplexHandler {
    private ThreadPoolExecutor threadPoolExecutor;
    private MessageHandler messageHandler;
    private Channel channel;
    private Codec codec;

    public NettyChannelHandler(Channel channel, MessageHandler messageHandler) {
        this.channel = channel;
        this.messageHandler = messageHandler;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                channel.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.value()));
    }

    public NettyChannelHandler(Channel channel, MessageHandler messageHandler, ThreadPoolExecutor threadPoolExecutor) {
        this(channel, messageHandler);
        this.threadPoolExecutor = threadPoolExecutor;
    }
}
