package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.hongxi.jaws.exception.JawsServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2020/7/6.
 */
public class NettyEncoder extends MessageToByteEncoder<byte[]> {
    private static final Logger log = LoggerFactory.getLogger(NettyEncoder.class);

    private final int maxContentLength;

    public NettyEncoder() {
        this(0);
    }

    public NettyEncoder(int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, ByteBuf out) throws Exception {
        if (maxContentLength > 0 && msg.length > maxContentLength) {
            log.warn("encode data length over of limit, size: {} > {}. remote={} local={}",
                    msg.length, maxContentLength, ctx.channel().remoteAddress(), ctx.channel().localAddress());
            throw new JawsServiceException("NettyEncoder encode data length over of limit, size: "
                    + msg.length + " > " + maxContentLength);
        }
        out.writeBytes(msg);
    }
}
