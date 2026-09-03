package org.hongxi.jaws.wire;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2PingFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection-level gRPC keepalive policy for the wire server.
 * <p>
 * Netty's {@code Http2FrameCodec} auto-ACKs incoming PING frames, so a standard
 * gRPC client's keepalive probes are answered by default. This handler adds the
 * gRPC {@code PERMIT_KEEPALIVE_TIME} semantics on top: PINGs that arrive faster
 * than the permitted interval trigger {@code GOAWAY} with the
 * {@code too_many_pings} debug data, exactly as grpc-java servers do — the
 * behavior standard clients are coded against.
 * <p>
 * gRPC spec values (gRPC keepalive gRFC A8) for reference: grpc-java's default
 * permit interval is 5 minutes. jaws defaults to the same 5 minutes, tunable
 * via the {@code permitPingIntervalMs} URL parameter; {@code 0} disables the
 * guard entirely (all PING intervals permitted).
 * <p>
 * Install per connection, between {@code http2_codec} and
 * {@code Http2MultiplexHandler} — PING frames are connection-level, not
 * per-stream. Non-PING frames are passed through untouched.
 *
 * @author shenhongxi
 */
public class WireKeepaliveHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireKeepaliveHandler.class);

    /** ASCII for "too_many_pings", the gRPC-conventional GOAWAY debug data. */
    static final byte[] TOO_MANY_PINGS = "too_many_pings".getBytes();

    private final long permitIntervalMs;
    private long lastPingTimeNanos = -1;

    /**
     * @param permitIntervalMs minimum permitted interval between keepalive PINGs;
     *                         {@code 0} disables the guard (permit all)
     */
    public WireKeepaliveHandler(long permitIntervalMs) {
        this.permitIntervalMs = permitIntervalMs;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Http2PingFrame ping)) {
            // Not a PING — stream frames and other connection frames pass through
            super.channelRead(ctx, msg);
            return;
        }
        log.info("WireKeepalive received PING: ack={}, permit={}ms, lastPing={}",
                ping.ack(), permitIntervalMs, lastPingTimeNanos);

        if (!ping.ack() && permitIntervalMs > 0) {
            long now = System.nanoTime();
            if (lastPingTimeNanos > 0) {
                long intervalMs = (now - lastPingTimeNanos) / 1_000_000;
                if (intervalMs < permitIntervalMs) {
                    log.warn("gRPC keepalive PING too frequent: interval={}ms, permitted={}ms, "
                                    + "remote={}, closing with GOAWAY too_many_pings",
                            intervalMs, permitIntervalMs, ctx.channel().remoteAddress());
                    // gRPC semantics: GOAWAY with too_many_pings debug data; the
                    // HTTP/2 error code ENHANCE_YOUR_CALM tells the peer to back off.
                    // The frame is auto-released once written.
                    ctx.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.ENHANCE_YOUR_CALM,
                                    Unpooled.wrappedBuffer(TOO_MANY_PINGS)))
                            .addListener(f -> ctx.close());
                    return;
                }
            }
            lastPingTimeNanos = now;
        }
        // ACK PING frames are silently consumed here — the actual PING reply
        // is handled by Http2FrameCodec's auto-ACK; no refcount to release.
    }
}
