package org.hongxi.jaws.wire;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WireKeepaliveHandler}: the gRPC PERMIT_KEEPALIVE_TIME guard
 * that answers standard clients' PINGs but GOAWAYs overly frequent ones with
 * the too_many_pings debug data.
 *
 * @author shenhongxi
 */
class WireKeepaliveHandlerTest {

    @Test
    void pingWithinIntervalPassesThrough() {
        EmbeddedChannel ch = new EmbeddedChannel(new WireKeepaliveHandler(60_000));
        // First PING always passes (no previous ping time)
        ch.writeInbound(new DefaultHttp2PingFrame(1, false));
        assertNull(ch.readOutbound(), "no GOAWAY for the first PING");
        assertTrue(ch.isOpen());
    }

    @Test
    void ackPingNeverTriggeredGuard() {
        EmbeddedChannel ch = new EmbeddedChannel(new WireKeepaliveHandler(60_000));
        ch.writeInbound(new DefaultHttp2PingFrame(1, false));
        ch.writeInbound(new DefaultHttp2PingFrame(1, true));  // ack, ignored
        ch.writeInbound(new DefaultHttp2PingFrame(1, true));  // ack again
        assertNull(ch.readOutbound());
        assertTrue(ch.isOpen());
    }

    @Test
    void rapidPingsTriggerGoAwayTooManyPings() throws Exception {
        // Permit interval 1000ms: a second PING within that window is too frequent
        EmbeddedChannel ch = new EmbeddedChannel(new WireKeepaliveHandler(1000));
        ch.writeInbound(new DefaultHttp2PingFrame(1, false));
        // Second PING arrives immediately (interval ≈ 0ms < 1000ms)
        ch.writeInbound(new DefaultHttp2PingFrame(1, false));
        // Run the close listener triggered after GOAWAY flush
        ch.runPendingTasks();
        DefaultHttp2GoAwayFrame goAway = ch.readOutbound();
        assertNotNull(goAway, "rapid PING must trigger GOAWAY");
        byte[] debug = new byte[goAway.content().readableBytes()];
        goAway.content().readBytes(debug);
        assertEquals("too_many_pings", new String(debug));
    }

    @Test
    void guardDisabledPermitsAllPings() {
        EmbeddedChannel ch = new EmbeddedChannel(new WireKeepaliveHandler(0));
        ch.writeInbound(new DefaultHttp2PingFrame(1, false));
        ch.writeInbound(new DefaultHttp2PingFrame(2, false));
        ch.writeInbound(new DefaultHttp2PingFrame(3, false));
        assertNull(ch.readOutbound());
        assertTrue(ch.isOpen());
    }

    @Test
    void nonPingMessagesPassThroughUnfiltered() {
        // The handler must not swallow stream frames: non-PING objects are
        // passed to the next handler via super.channelRead
        EmbeddedChannel ch = new EmbeddedChannel(new WireKeepaliveHandler(60_000));
        String marker = "not-a-ping";
        ch.writeInbound(marker);
        assertEquals(marker, ch.readInbound());
    }

    @Test
    void spacedPingsDoNotTriggerGoAway() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new WireKeepaliveHandler(2));
        ch.writeInbound(new DefaultHttp2PingFrame(1, false));
        Thread.sleep(10);
        ch.writeInbound(new DefaultHttp2PingFrame(2, false));
        Thread.sleep(10);
        ch.writeInbound(new DefaultHttp2PingFrame(3, false));
        assertNull(ch.readOutbound());
        assertTrue(ch.isOpen());
    }
}
