package org.hongxi.summer.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.hongxi.summer.codec.Codec;
import org.hongxi.summer.common.SummerConstants;
import org.hongxi.summer.exception.SummerFrameworkException;
import org.hongxi.summer.exception.SummerServiceException;
import org.hongxi.summer.rpc.Response;
import org.hongxi.summer.transport.Channel;
import org.hongxi.summer.CodecUtils;
import org.hongxi.summer.common.util.SummerFrameworkUtils;
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
        if (in.readableBytes() <= SummerConstants.NETTY_HEADER_LENGTH) {
            return;
        }

        in.markReaderIndex();
        short type = in.readShort();
        if (type != SummerConstants.NETTY_MAGIC_TYPE) {
            in.resetReaderIndex();
            throw new SummerFrameworkException("NettyDecoder transport header not support, type: " + type);
        }
        in.skipBytes(1);
        int rpcVersion = (in.readByte() & 0xff) >>> 3;
        decode0(ctx, in, out);
    }

    private void decode0(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        long startTime = System.currentTimeMillis();
        in.resetReaderIndex();
        if (in.readableBytes() < 21) {
            return;
        }
        in.skipBytes(2);
        in.readByte(); // v2 flag: (b & 0x01) == 0x00
        in.skipBytes(2);
        long requestId = in.readLong();
        int size = 13;
        int metaSize = in.readInt();
        size += 4;
        if (metaSize > 0) {
            size += metaSize;
            if (in.readableBytes() < metaSize) {
                in.resetReaderIndex();
                return;
            }
            in.skipBytes(metaSize);
        }
        if (in.readableBytes() < 4) {
            in.resetReaderIndex();
            return;
        }
        int bodySize = in.readInt();
        checkMaxContent(bodySize, ctx, requestId);
        size += 4;
        if (bodySize > 0) {
            size += bodySize;
            if (in.readableBytes() < bodySize) {
                in.resetReaderIndex();
                return;
            }
        }
        byte[] data = new byte[size];
        in.resetReaderIndex();
        in.readBytes(data);

        NettyMessage message = new NettyMessage(requestId, data);
        message.setStartTime(startTime);
        out.add(message);
    }

    private void checkMaxContent(int contentLength, ChannelHandlerContext ctx, long requestId) throws Exception {
        if (maxContentLength > 0 && contentLength > maxContentLength) {
            logger.warn("transport data content length over of limit, size: {}  > {}. remote={} local={}",
                    contentLength, maxContentLength, ctx.channel().remoteAddress(), ctx.channel().localAddress());
            Exception e = new SummerServiceException("NettyDecoder transport data content length over of limit, size: " + contentLength + " > " + maxContentLength);
            Response response = SummerFrameworkUtils.buildErrorResponse(requestId, e);
            byte[] msg = CodecUtils.encodeObjectToBytes(channel, codec, response);
            ctx.channel().writeAndFlush(msg);
            throw e;
        }
    }
}
