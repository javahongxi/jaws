package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.hongxi.jaws.codec.Codec;
import org.hongxi.jaws.codec.CodecUtils;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.protocol.jaws.JawsCodec;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.transport.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by shenhongxi on 2020/7/6.
 */
public class NettyDecoder extends ByteToMessageDecoder {
    private static final Logger log = LoggerFactory.getLogger(NettyDecoder.class);

    private final Codec codec;
    private final Channel channel;
    private final int maxContentLength;

    public NettyDecoder(Codec codec, Channel channel, int maxContentLength) {
        this.codec = codec;
        this.channel = channel;
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() <= JawsCodec.HEADER_LENGTH) {
            return;
        }

        in.markReaderIndex();
        short type = in.readShort();
        if (type != JawsConstants.NETTY_MAGIC_TYPE) {
            in.resetReaderIndex();
            throw new JawsFrameworkException("NettyDecoder transport header not support, type: " + type);
        }

        in.resetReaderIndex();
        // skip magic num
        in.skipBytes(2);
        byte messageType = (byte) in.readShort();
        long requestId = in.readLong();
        int dataLength = in.readInt();

        boolean isRequest = messageType == JawsConstants.FLAG_REQUEST;

        // Reject oversized messages to prevent OOM, without closing the connection
        if (maxContentLength > 0 && dataLength > maxContentLength) {
            log.warn("transport data content length over of limit, size: {} > {}. remote={} local={}",
                    dataLength, maxContentLength, ctx.channel().remoteAddress(), ctx.channel().localAddress());
            // skip all readable bytes so ByteToMessageDecoder won't re-invoke decode()
            in.skipBytes(in.readableBytes());
            if (isRequest) {
                Exception e = new JawsServiceException(
                        "NettyDecoder transport data content length over of limit, size: " + dataLength + " > " + maxContentLength);
                Response response = JawsFrameworkUtils.buildErrorResponse(requestId, e);
                byte[] msg = CodecUtils.encodeObjectToBytes(channel, codec, response);
                ctx.channel().writeAndFlush(msg);
            }
            return;
        }
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }
        byte[] data = new byte[dataLength];
        in.readBytes(data);

        NettyMessage message = new NettyMessage(isRequest, requestId, data);
        out.add(message);
    }
}