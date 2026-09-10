package org.hongxi.jaws.wire;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
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
 * This implements the gRPC keepalive gRFC A8 server guard, both of its halves:
 * <ul>
 *   <li>{@code GRPC_ARG_HTTP2_MIN_RECV_PING_INTERVAL_WITHOUT_DATA_MS} —
 *       {@code permitPingIntervalMs}, default 300000. The interval is only
 *       judged <b>without data</b>: PINGs that bracket real traffic are legal,
 *       which is how a healthy grpcurl client behaves (probe, call, probe).</li>
 *   <li>{@code GRPC_ARG_HTTP2_MAX_PING_STRIKES} — {@code permitPingStrikes},
 *       default 2. Violations accumulate as strikes; GOAWAY goes out when the
 *       allowance is exhausted, not on the first violation, because GOAWAY
 *       executes every in-flight stream.</li>
 * </ul>
 * {@code permitPingIntervalMs=0} disables the guard entirely;
 * {@code permitPingStrikes=0} accepts any number of bad PINGs — a different
 * switch, kept distinct as in grpc-java.
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

    /** Default allowance before GOAWAY, same as GRPC_ARG_HTTP2_MAX_PING_STRIKES. */
    public static final int DEFAULT_MAX_PING_STRIKES = 2;

    private final long permitIntervalMs;
    private final int maxStrikes;
    private long lastPingTimeNanos = -1;
    /** Last time any DATA/HEADERS frame moved on this connection, outbound included. */
    private long lastDataTimeNanos = -1;
    private int strikes;

    /**
     * @param permitIntervalMs minimum permitted interval between keepalive PINGs
     *                         judged without data; {@code 0} disables the guard
     */
    public WireKeepaliveHandler(long permitIntervalMs) {
        this(permitIntervalMs, DEFAULT_MAX_PING_STRIKES);
    }

    /**
     * @param permitIntervalMs minimum permitted interval between keepalive PINGs;
     *                         {@code 0} disables the guard (permit all)
     * @param maxStrikes       bad PINGs tolerated before GOAWAY;
     *                         {@code 0} permits any number (grpc-java semantics)
     */
    public WireKeepaliveHandler(long permitIntervalMs, int maxStrikes) {
        this.permitIntervalMs = permitIntervalMs;
        this.maxStrikes = maxStrikes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame || msg instanceof Http2DataFrame) {
            // gRFC A8 judges the interval "without data": traffic on the
            // connection restarts the judgment window for the next PING.
            lastDataTimeNanos = System.nanoTime();
        }
        if (!(msg instanceof Http2PingFrame ping)) {
            // Not a PING — stream frames and other connection frames pass through
            super.channelRead(ctx, msg);
            return;
        }
        log.info("WireKeepalive received PING: ack={}, permit={}ms, strikes={}/{}",
                ping.ack(), permitIntervalMs, strikes, maxStrikes);

        if (!ping.ack() && permitIntervalMs > 0) {
            long now = System.nanoTime();
            boolean precededByData = lastDataTimeNanos > lastPingTimeNanos;
            if (lastPingTimeNanos > 0 && !precededByData) {
                long intervalMs = (now - lastPingTimeNanos) / 1_000_000;
                if (intervalMs < permitIntervalMs) {
                    strikes++;
                    if (maxStrikes > 0 && strikes >= maxStrikes) {
                        log.warn("gRPC keepalive PING strikes exhausted: strikes={}, permitted={}ms, "
                                        + "remote={}, closing with GOAWAY too_many_pings",
                                strikes, permitIntervalMs, ctx.channel().remoteAddress());
                        // gRPC semantics: GOAWAY with too_many_pings debug data; the
                        // HTTP/2 error code ENHANCE_YOUR_CALM tells the peer to back off.
                        ctx.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.ENHANCE_YOUR_CALM,
                                        Unpooled.wrappedBuffer(TOO_MANY_PINGS)))
                                .addListener(f -> ctx.close());
                        return;
                    }
                    log.info("gRPC keepalive PING strike {} of {} (interval={}ms < {}ms)",
                            strikes, maxStrikes, intervalMs, permitIntervalMs);
                    // Fall through: a strike still refreshes the reference ping
                    // time, otherwise every following PING would count again.
                    lastPingTimeNanos = now;
                    return;
                }
            }
            lastPingTimeNanos = now;
        }
        // ACK PING frames are silently consumed here — the actual PING reply
        // is handled by Http2FrameCodec's auto-ACK; no refcount to release.
    }
}
