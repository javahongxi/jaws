package org.hongxi.jaws.wire;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WireKeepaliveHandler}: the gRPC PERMIT_KEEPALIVE_TIME guard
 * that answers standard clients' PINGs but GOAWAYs overly frequent ones with
 * the too_many_pings debug data.
 * <p>
 * The gRFC A8 semantics are narrower than a bare interval check, and these
 * tests pin all three narrows: the interval is only judged
 * <b>without data</b> — PINGs that bracket real traffic (grpcurl does exactly
 * that: one PING before the call, one right after the data) are legitimate;
 * violations are counted as <b>strikes</b> — grpc-java's default allowance is
 * two before GOAWAY, not zero; and {@code strikes=0} permits any number of
 * bad PINGs, which is a different switch from {@code interval=0}.
 *
 * @author shenhongxi
 */
class WireKeepaliveHandlerTest {

    private static final long INTERVAL = 60_000;

    private static EmbeddedChannel channel(long intervalMs, int maxStrikes) {
        return new EmbeddedChannel(new WireKeepaliveHandler(intervalMs, maxStrikes));
    }

    @Test
    void pingWithinIntervalPassesThrough() {
        EmbeddedChannel ch = channel(INTERVAL, 2);
        // First PING always passes (no previous ping time)
        ch.writeInbound(ping(1));
        assertNull(ch.readOutbound(), "no GOAWAY for the first PING");
        assertTrue(ch.isOpen());
    }

    @Test
    void ackPingNeverTriggeredGuard() {
        EmbeddedChannel ch = channel(INTERVAL, 2);
        ch.writeInbound(ping(1));
        ch.writeInbound(ack(1));  // ack, ignored
        ch.writeInbound(ack(1));  // ack again
        assertNull(ch.readOutbound());
        assertTrue(ch.isOpen());
    }

    @Test
    void firstViolationIsAStrikeNotAGOAWAY() {
        // The grpc-java default allowance is two strikes, so the first too-frequent
        // PING must be tolerated: killing on the first one executes in-flight
        // streams (that is how a healthy grpcurl streaming call once died at
        // item 1 of 3).
        EmbeddedChannel ch = channel(INTERVAL, 2);
        ch.writeInbound(ping(1));
        ch.writeInbound(ping(2)); // interval ≈ 0ms < 60s → strike 1
        assertNull(ch.readOutbound(), "the first violation is a strike, not a GOAWAY");
        assertTrue(ch.isOpen());
    }

    @Test
    void rapidPingsTriggerGoAwayTooManyPings() throws Exception {
        EmbeddedChannel ch = channel(INTERVAL, 2);
        ch.writeInbound(ping(1));
        ch.writeInbound(ping(2)); // strike 1
        ch.writeInbound(ping(3)); // strike 2 → GOAWAY
        ch.runPendingTasks();
        DefaultHttp2GoAwayFrame goAway = ch.readOutbound();
        assertNotNull(goAway, "the second violation must trigger GOAWAY");
        byte[] debug = new byte[goAway.content().readableBytes()];
        goAway.content().readBytes(debug);
        assertEquals("too_many_pings", new String(debug));
    }

    @Test
    void pingSurroundedByDataIsNotAViolation() {
        // gRFC A8 counts the interval "without data": grpcurl's back-to-back PING
        // around an actual call is legal even though the raw interval is 0ms.
        EmbeddedChannel ch = channel(INTERVAL, 1);
        ch.writeInbound(ping(1));
        ch.writeInbound(new DefaultHttp2HeadersFrame(
                new io.netty.handler.codec.http2.DefaultHttp2Headers()
                        .method("POST").path("/interop.Greeter/SayHello")));
        ch.readInbound(); // consumed downstream
        ch.writeInbound(new DefaultHttp2DataFrame(io.netty.buffer.Unpooled.EMPTY_BUFFER, false));
        ch.readInbound(); // consumed downstream
        ch.writeInbound(ping(2)); // interval ≈ 0ms, but DATA flowed between
        assertNull(ch.readOutbound(), "PINGs bracketing traffic are not violations");
        assertTrue(ch.isOpen());
    }

    @Test
    void dataBeforeThePingResetsTheIntervalClock() {
        // Even after a strike, traffic on the connection resets the judgment
        // window: the next PING is compared against the last DATA, not the last
        // PING. With an allowance of two, ping(3) after data must not burn the
        // second strike — only a data-free violation may.
        EmbeddedChannel ch = channel(INTERVAL, 2);
        ch.writeInbound(ping(1));
        ch.writeInbound(ping(2)); // strike 1 of 2, tolerated
        // Consume the DATA frame as the next handler would, so releasing it
        // does not close the embedded channel.
        ch.writeInbound(new DefaultHttp2DataFrame(io.netty.buffer.Unpooled.EMPTY_BUFFER, false));
        assertNotNull(ch.readInbound(), "DATA must pass through to the next handler");
        ch.writeInbound(ping(3)); // preceded by data → not a violation, still 1 strike
        assertNull(ch.readOutbound(), "a PING preceded by data is not a strike");
        assertTrue(ch.isOpen());
        ch.writeInbound(ping(4)); // no data since ping(3) → strike 2 of 2 → GOAWAY
        ch.runPendingTasks();
        assertNotNull(ch.readOutbound(), "a data-free violation exhausts the allowance");
    }

    @Test
    void zeroStrikesPermitsAnyNumberOfBadPings() {
        // strikes=0 means "accept any number of bad pings" — a different switch
        // from interval=0 (guard off). grpc-java keeps that distinction.
        EmbeddedChannel ch = channel(INTERVAL, 0);
        for (long i = 1; i <= 5; i++) {
            ch.writeInbound(ping(i));
        }
        assertNull(ch.readOutbound());
        assertTrue(ch.isOpen());
    }

    @Test
    void guardDisabledPermitsAllPings() {
        EmbeddedChannel ch = channel(0, 2);
        ch.writeInbound(ping(1));
        ch.writeInbound(ping(2));
        ch.writeInbound(ping(3));
        assertNull(ch.readOutbound());
        assertTrue(ch.isOpen());
    }

    @Test
    void nonPingMessagesPassThroughUnfiltered() {
        // The handler must not swallow stream frames: non-PING objects are
        // passed to the next handler via super.channelRead
        EmbeddedChannel ch = channel(INTERVAL, 2);
        String marker = "not-a-ping";
        ch.writeInbound(marker);
        assertEquals(marker, ch.readInbound());
    }

    @Test
    void spacedPingsDoNotTriggerGoAway() throws Exception {
        EmbeddedChannel ch = channel(2, 1);
        ch.writeInbound(ping(1));
        Thread.sleep(10);
        ch.writeInbound(ping(2));
        Thread.sleep(10);
        ch.writeInbound(ping(3));
        assertNull(ch.readOutbound());
        assertTrue(ch.isOpen());
    }

    private static Http2PingFrame ping(long content) {
        return new DefaultHttp2PingFrame(content, false);
    }

    private static Http2PingFrame ack(long content) {
        return new DefaultHttp2PingFrame(content, true);
    }
}
