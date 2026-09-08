package org.hongxi.jaws.wire;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection lifecycle management for the gRPC wire server. Monitors:
 * <ul>
 *   <li><b>Max connection idle</b>: when no streams are active and the idle
 *       period exceeds the configured threshold, sends GOAWAY and closes</li>
 *   <li><b>Max connection age</b>: when a connection has been alive longer
 *       than the configured max age, sends GOAWAY, waits a grace period,
 *       then force-closes</li>
 * </ul>
 * <p>
 * Active stream tracking uses an atomic counter incremented/decremented as
 * streams are created and closed. The idle check only fires when the counter
 * reaches zero.
 * <p>
 * Install on the connection channel pipeline between {@code http2_codec}
 * and {@code http2_multiplex}.
 *
 * @author shenhongxi
 * @see WireServer#addOptionalChannelHandlers
 */
public class WireConnectionLifecycleHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireConnectionLifecycleHandler.class);

    private static final byte[] GRACEFUL_CLOSE = "max_age".getBytes();

    private final long maxIdleMs;
    private final long maxAgeMs;
    private final long graceMs;
    private final ScheduledExecutorService scheduler;

    /** Number of currently active HTTP/2 streams on this connection. */
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    private ScheduledFuture<?> idleCheckFuture;
    private ScheduledFuture<?> ageCloseFuture;
    private long lastStreamCloseNanos;
    private boolean goawaySent;

    /**
     * @param maxIdleMs max idle time (no active streams) before GOAWAY; 0 disables
     * @param maxAgeMs  max connection lifetime before graceful close; 0 disables
     * @param graceMs   grace period after GOAWAY before force-close
     * @param scheduler the scheduler for periodic checks
     */
    public WireConnectionLifecycleHandler(long maxIdleMs, long maxAgeMs, long graceMs,
                                          ScheduledExecutorService scheduler) {
        this.maxIdleMs = maxIdleMs;
        this.maxAgeMs = maxAgeMs;
        this.graceMs = graceMs;
        this.scheduler = scheduler;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        lastStreamCloseNanos = System.nanoTime();

        if (maxIdleMs > 0) {
            // Check idle status periodically (every maxIdleMs / 2 or at least 1s)
            long checkInterval = Math.max(1000, maxIdleMs / 2);
            idleCheckFuture = scheduler.scheduleAtFixedRate(
                    () -> checkIdle(ctx), checkInterval, checkInterval, TimeUnit.MILLISECONDS);
        }

        if (maxAgeMs > 0) {
            ageCloseFuture = scheduler.schedule(() -> initiateAgeClose(ctx),
                    maxAgeMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Track stream creation/closure via Http2MultiplexHandler's child
        // channel lifecycle events propagated to the parent pipeline.
        // However, stream-level events are typically confined to the child
        // channel. For a simpler approach, we rely on the stream serverHandler
        // to call streamOpened()/streamClosed() explicitly.
        super.channelRead(ctx, msg);
    }

    /**
     * Called by the stream handler when a new stream is opened.
     */
    public void streamOpened() {
        activeStreams.incrementAndGet();
    }

    /**
     * Called by the stream handler when a stream is closed.
     */
    public void streamClosed() {
        if (activeStreams.decrementAndGet() == 0) {
            lastStreamCloseNanos = System.nanoTime();
        }
    }

    private void checkIdle(ChannelHandlerContext ctx) {
        if (goawaySent || !ctx.channel().isActive()) {
            return;
        }
        if (activeStreams.get() > 0) {
            // Streams are active, not idle
            return;
        }
        long idleMs = (System.nanoTime() - lastStreamCloseNanos) / 1_000_000;
        if (idleMs >= maxIdleMs) {
            log.info("Connection idle for {}ms (max={}ms), sending GOAWAY: remote={}",
                    idleMs, maxIdleMs, ctx.channel().remoteAddress());
            sendGoAway(ctx, Http2Error.NO_ERROR, new byte[0]);
        }
    }

    private void initiateAgeClose(ChannelHandlerContext ctx) {
        if (goawaySent || !ctx.channel().isActive()) {
            return;
        }
        log.info("Connection max age reached ({}ms), sending GOAWAY: remote={}",
                maxAgeMs, ctx.channel().remoteAddress());
        sendGoAway(ctx, Http2Error.NO_ERROR, GRACEFUL_CLOSE);

        // Schedule force-close after grace period
        scheduler.schedule(() -> {
            if (ctx.channel().isActive()) {
                log.info("Grace period expired, force-closing connection: remote={}",
                        ctx.channel().remoteAddress());
                ctx.close();
            }
        }, graceMs, TimeUnit.MILLISECONDS);
    }

    private void sendGoAway(ChannelHandlerContext ctx, Http2Error error, byte[] debugData) {
        goawaySent = true;
        ctx.writeAndFlush(new DefaultHttp2GoAwayFrame(error, Unpooled.wrappedBuffer(debugData)))
                .addListener(f -> {
                    if (error == Http2Error.NO_ERROR && debugData.length == 0) {
                        // Idle close: close immediately after GOAWAY
                        ctx.close();
                    }
                    // Age close: wait for grace period (handled by scheduled task)
                });
        // Cancel idle checks
        if (idleCheckFuture != null) {
            idleCheckFuture.cancel(false);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (idleCheckFuture != null) {
            idleCheckFuture.cancel(false);
        }
        if (ageCloseFuture != null) {
            ageCloseFuture.cancel(false);
        }
    }
}
