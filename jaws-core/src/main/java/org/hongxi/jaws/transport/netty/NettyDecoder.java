package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.hongxi.jaws.codec.Codec;
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
        if (in.readableBytes() < JawsCodec.HEADER_LENGTH) {
            return;
        }

        in.markReaderIndex();

        // bytes 0-1: magic
        short magic = in.readShort();
        if (magic != JawsCodec.MAGIC) {
            in.resetReaderIndex();
            throw new JawsFrameworkException("NettyDecoder magic not match: " + magic);
        }

        // byte 2: version (skip, validated by JawsCodec.decode)
        in.skipBytes(1);
        // byte 3: flag
        byte flag = in.readByte();

        // Heartbeat frame: event bit set — consume and skip, do not pass to business layer
        if ((flag & JawsCodec.FLAG_EVENT) != 0) {
            int bodyLen = in.readInt(); // bytes 12-15
            in.skipBytes(Math.max(0, bodyLen));
            return;
        }

        // bytes 4-11: requestId
        long requestId = in.readLong();
        // bytes 12-15: body length
        int bodyLength = in.readInt();

        boolean isRequest = (flag & JawsCodec.MASK) == JawsCodec.FLAG_REQUEST;

        // Reject oversized messages to prevent OOM, without closing the connection
        if (maxContentLength > 0 && bodyLength > maxContentLength) {
            log.warn("transport data content length over of limit, size: {} > {}. remote={} local={}",
                    bodyLength, maxContentLength, ctx.channel().remoteAddress(), ctx.channel().localAddress());
            in.skipBytes(in.readableBytes());
            if (isRequest) {
                Exception e = new JawsServiceException(
                        "NettyDecoder transport data content length over of limit, size: " + bodyLength + " > " + maxContentLength);
                Response response = JawsFrameworkUtils.buildErrorResponse(requestId, e);
                ByteBuf msg = ctx.alloc().buffer();
                codec.encode(channel, response, msg);
                ctx.channel().writeAndFlush(msg);
            }
            return;
        }

        if (in.readableBytes() < bodyLength) {
            in.resetReaderIndex();
            return;
        }

        // Pass ByteBuf directly to JawsCodec.decode (zero-copy, no frame byte[] allocation)
        in.resetReaderIndex();
        // Retain the buffer since the caller (ByteToMessageDecoder pipeline) may release it;
        // NettyChannelHandler is responsible for releasing after processing.
        ByteBuf frame = in.readRetainedSlice(JawsCodec.HEADER_LENGTH + bodyLength);

        NettyMessage message = new NettyMessage(isRequest, requestId, frame);
        out.add(message);
    }
}