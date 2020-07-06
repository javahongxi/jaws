package org.hongxi.summer.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.hongxi.summer.codec.Codec;
import org.hongxi.summer.transport.Channel;

import java.util.List;

/**
 * Created by shenhongxi on 2020/7/6.
 */
public class NettyDecoder extends ByteToMessageDecoder {
    private Codec codec;
    private Channel channel;
    private int maxContentLength;

    public NettyDecoder(Codec codec, Channel channel, int maxContentLength) {
        this.codec = codec;
        this.channel = channel;
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {

    }
}
