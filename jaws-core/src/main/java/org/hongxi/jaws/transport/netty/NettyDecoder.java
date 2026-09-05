package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.transport.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Netty {@link ByteToMessageDecoder} that frames the jaws protocol:
 * a 16-byte header (magic, version, flag, requestId, body length) followed
 * by the body. The flag byte distinguishes requests from responses and, when
 * {@code (flag & JawsCodec.FLAG_EVENT) != 0}, indicates a heartbeat frame that is
 * consumed here and never forwarded to the business layer.
 * <p>
 * Bodies exceeding {@code maxContentLength} are rejected (answered with an
 * error response if a request) without closing the connection. Valid frames
 * are emitted as zero-copy {@link DecodedFrame} holding a retained
 * {@link ByteBuf} slice, which {@link NettyChannelHandler} releases after
 * processing.
 * <p>
 * Created by shenhongxi on 2020/7/6.
 */
public class NettyDecoder extends ByteToMessageDecoder {
    private static final Logger log = LoggerFactory.getLogger(NettyDecoder.class);

    private final Channel channel;
    private final int maxContentLength;

    /**
     * Remaining body bytes of a rejected oversized frame that have not arrived yet.
     * While positive, decode() drains them instead of parsing, keeping the stream
     * in sync without closing the connection.
     */
    private long bytesToSkip;

    public NettyDecoder(Channel channel, int maxContentLength) {
        this.channel = channel;
        this.maxContentLength = maxContentLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // Drain the remainder of a rejected oversized frame body
        if (bytesToSkip > 0) {
            int drained = (int) Math.min(bytesToSkip, in.readableBytes());
            in.skipBytes(drained);
            bytesToSkip -= drained;
            if (bytesToSkip > 0) {
                return;
            }
        }

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
            in.skipBytes(8); // skip requestId (bytes 4-11)
            int bodyLen = in.readInt(); // bytes 12-15
            in.skipBytes(Math.max(0, bodyLen));
            return;
        }

        // bytes 4-11: requestId
        long requestId = in.readLong();
        // bytes 12-15: body length
        int bodyLength = in.readInt();

        boolean isRequest = (flag & JawsCodec.MASK) == JawsCodec.FLAG_REQUEST;

        if (bodyLength < 0) {
            throw new JawsFrameworkException("NettyDecoder negative body length: " + bodyLength);
        }

        // Reject oversized messages to prevent OOM, without closing the connection
        if (maxContentLength > 0 && bodyLength > maxContentLength) {
            log.warn("transport data content length exceeds limit, size: {} > {}. remote={} local={}",
                    bodyLength, maxContentLength, ctx.channel().remoteAddress(), ctx.channel().localAddress());
            // The body may arrive in later chunks; drain the rest in subsequent
            // decode() calls to keep the stream in sync.
            int drained = Math.min(bodyLength, in.readableBytes());
            in.skipBytes(drained);
            bytesToSkip = bodyLength - drained;
            if (isRequest) {
                Exception e = new JawsServiceException(
                        "NettyDecoder transport data content length exceeds limit, size: " + bodyLength + " > " + maxContentLength);
                Response response = RpcUtils.buildErrorResponse(requestId, e);
                ByteBuf msg = ctx.alloc().buffer();
                JawsCodec.encode(channel, response, msg);
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

        DecodedFrame decodedFrame = new DecodedFrame(isRequest, requestId, frame);
        out.add(decodedFrame);
    }
}