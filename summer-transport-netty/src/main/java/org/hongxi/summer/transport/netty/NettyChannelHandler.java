package org.hongxi.summer.transport.netty;

import io.netty.channel.ChannelDuplexHandler;
import org.hongxi.summer.codec.Codec;
import org.hongxi.summer.common.URLParamType;
import org.hongxi.summer.core.extension.ExtensionLoader;
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

    public NettyChannelHandler(MessageHandler messageHandler, Channel channel) {
        this.messageHandler = messageHandler;
        this.channel = channel;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                channel.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.getValue()));
    }

    public NettyChannelHandler(ThreadPoolExecutor threadPoolExecutor, MessageHandler messageHandler, Channel channel) {
        this.threadPoolExecutor = threadPoolExecutor;
        this.messageHandler = messageHandler;
        this.channel = channel;
        this.codec = ExtensionLoader.getExtensionLoader(Codec.class).getExtension(
                channel.getUrl().getParameter(URLParamType.codec.getName(), URLParamType.codec.getValue()));
    }
}
