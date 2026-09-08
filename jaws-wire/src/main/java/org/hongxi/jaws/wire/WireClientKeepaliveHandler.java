package org.hongxi.jaws.wire;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Connection-level gRPC keepalive for the client side. Sends periodic PING
 * frames to the server; if no ACK (or any data) is received within the
 * configured timeout, the connection is considered dead and closed — the
 * next {@code activeChannel()} call triggers a reconnect.
 * <p>
 * Mirrors grpc-java's {@code KeepAliveManager} state machine:
 * <pre>
 *   IDLE → (keepaliveTime elapsed) → PING_SCHEDULED → (write PING) → PING_SENT
 *       → (ACK/data received) → IDLE
 *       → (timeout elapsed)   → close connection
 * </pre>
 * <p>
 * Any inbound data (not just PING ACKs) resets the idle timer, matching
 * grpc-java's PING_DELAYED behaviour: active RPCs suppress keepalive probes.
 * <p>
 * Install on the connection channel pipeline between {@code http2_codec}
 * and {@code http2_multiplex}.
 *
 * @author shenhongxi
 * @see WireClient#addOptionalChannelHandlers
 */
public class WireClientKeepaliveHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireClientKeepaliveHandler.class);

    private final long keepaliveTimeMs;
    private final long keepaliveTimeoutMs;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pingFuture;
    private ScheduledFuture<?> timeoutFuture;
    private long lastActivityNanos;
    private boolean pingSent;
    private boolean closed;

    /**
     * @param keepaliveTimeMs    interval between keepalive probes; 0 disables
     * @param keepaliveTimeoutMs timeout waiting for ACK before closing
     * @param scheduler          the scheduler for PING and timeout tasks
     */
    public WireClientKeepaliveHandler(long keepaliveTimeMs, long keepaliveTimeoutMs,
                                      ScheduledExecutorService scheduler) {
        this.keepaliveTimeMs = keepaliveTimeMs;
        this.keepaliveTimeoutMs = keepaliveTimeoutMs;
        this.scheduler = scheduler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        lastActivityNanos = System.nanoTime();
        schedulePing(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Any inbound data resets the activity clock; PING ACKs clear the
        // pending timeout (the server is alive)
        lastActivityNanos = System.nanoTime();
        if (msg instanceof Http2PingFrame ping && ping.ack()) {
            cancelTimeout();
            pingSent = false;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closed = true;
        cancelPing();
        cancelTimeout();
    }

    private void schedulePing(ChannelHandlerContext ctx) {
        if (closed || keepaliveTimeMs <= 0) {
            return;
        }
        long delay = keepaliveTimeMs - (System.nanoTime() - lastActivityNanos) / 1_000_000;
        if (delay <= 0) {
            delay = 0;
        }
        pingFuture = scheduler.schedule(() -> sendPing(ctx), delay, TimeUnit.MILLISECONDS);
    }

    private void sendPing(ChannelHandlerContext ctx) {
        if (closed || !ctx.channel().isActive()) {
            return;
        }
        // Check if enough idle time has passed
        long idleMs = (System.nanoTime() - lastActivityNanos) / 1_000_000;
        if (idleMs < keepaliveTimeMs) {
            // Not idle enough yet, reschedule (PING_DELAYED semantics)
            schedulePing(ctx);
            return;
        }
        // Send the PING
        ctx.writeAndFlush(new DefaultHttp2PingFrame(0L));
        pingSent = true;
        // Schedule timeout: if no ACK within timeout, close the connection
        timeoutFuture = scheduler.schedule(() -> onPingTimeout(ctx),
                keepaliveTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void onPingTimeout(ChannelHandlerContext ctx) {
        if (closed || !ctx.channel().isActive()) {
            return;
        }
        if (pingSent) {
            log.warn("gRPC keepalive timeout: no PING ACK within {}ms, closing connection to {}",
                    keepaliveTimeoutMs, ctx.channel().remoteAddress());
            closed = true;
            ctx.close();
        }
    }

    private void cancelPing() {
        if (pingFuture != null) {
            pingFuture.cancel(false);
            pingFuture = null;
        }
    }

    private void cancelTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }
}
