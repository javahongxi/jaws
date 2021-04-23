package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.hongxi.jaws.codec.CodecUtils;
import org.hongxi.jaws.codec.Codec;
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
    private static final Logger logger = LoggerFactory.getLogger(NettyDecoder.class);

    private Codec codec;
    private Channel channel;
    private int maxContentLength;

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
        in.skipBytes(1);
        int rpcVersion = (in.readByte() & 0xff) >>> 3;
        decodeV1(ctx, in, out);
    }

    private void decodeV1(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        long startTime = System.currentTimeMillis();
        in.resetReaderIndex();
        in.skipBytes(2);// skip magic num
        byte messageType = (byte) in.readShort();
        long requestId = in.readLong();
        int dataLength = in.readInt();

        boolean isRequest = messageType == JawsConstants.FLAG_REQUEST;

        checkMaxContent(dataLength, ctx, in, isRequest, requestId);
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }
        byte[] data = new byte[dataLength];
        in.readBytes(data);

        NettyMessage message = new NettyMessage(isRequest, requestId, data);
        out.add(message);
        message.setStartTime(startTime);
    }

    private void checkMaxContent(int dataLength, ChannelHandlerContext ctx, ByteBuf byteBuf, boolean isRequest, long requestId) throws Exception {
        if (maxContentLength > 0 && dataLength > maxContentLength) {
            logger.warn("transport data content length over of limit, size: {}  > {}. remote={} local={}",
                    dataLength, maxContentLength, ctx.channel().remoteAddress(), ctx.channel().localAddress());
            // skip all readable Bytes in order to release this no-readable bytebuf in super.channelRead()
            // that avoid this.decode() being invoked again after channel.close()
            byteBuf.skipBytes(byteBuf.readableBytes());
            Exception e = new JawsServiceException("NettyDecoder transport data content length over of limit, size: " + dataLength + " > " + maxContentLength);
            if (isRequest) {
                Response response = JawsFrameworkUtils.buildErrorResponse(requestId, e);
                byte[] msg = CodecUtils.encodeObjectToBytes(channel, codec, response);
                ctx.channel().writeAndFlush(msg);
            }
            throw e;
        }
    }
}
