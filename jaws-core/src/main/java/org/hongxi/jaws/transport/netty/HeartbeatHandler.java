package org.hongxi.jaws.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;
import org.hongxi.jaws.transport.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles heartbeat frames driven by {@link io.netty.handler.timeout.IdleStateHandler} events.
 * <p>
 * <ul>
 *   <li><b>Writer idle</b>: sends a heartbeat frame to keep the connection alive
 *       (prevents NAT / load-balancer mapping expiry).</li>
 *   <li><b>Reader idle</b>: closes the connection when no data has been received
 *       from the remote peer for too long (peer crash detection).</li>
 * </ul>
 * <p>
 * Heartbeat frames are 16-byte headers with {@code FLAG_EVENT} set and zero-length body.
 * They are encoded via {@link Codec#encodeHeartbeat(ByteBuf)} and consumed silently
 * by {@link NettyDecoder} without entering the business thread pool.
 *
 * @see Codec#encodeHeartbeat(ByteBuf)
 * @see NettyDecoder
 */
public class HeartbeatHandler extends ChannelDuplexHandler {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    private final Codec codec;

    public HeartbeatHandler(Codec codec) {
        this.codec = codec;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleEvent) {
            switch (idleEvent.state()) {
                case WRITER_IDLE:
                    sendHeartbeat(ctx);
                    break;
                case READER_IDLE:
                    log.warn("heartbeat reader idle timeout, closing connection. remote={} local={}",
                            ctx.channel().remoteAddress(), ctx.channel().localAddress());
                    ctx.close();
                    break;
                default:
                    break;
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    private void sendHeartbeat(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) {
            return;
        }
        ByteBuf buf = ctx.alloc().buffer(Codec.HEADER_LENGTH);
        codec.encodeHeartbeat(buf);
        ctx.writeAndFlush(buf);
        log.debug("heartbeat sent. remote={} local={}",
                ctx.channel().remoteAddress(), ctx.channel().localAddress());
    }
}
