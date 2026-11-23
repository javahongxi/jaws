package org.hongxi.jaws.wire;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WireGoAwayHandler}: the client-side handler that reacts
 * to server-sent GOAWAY frames by closing the connection and triggering
 * an immediate reconnect.
 * <p>
 * Since {@code WireGoAwayHandler} delegates to {@code WireClient} for
 * reconnect and connectivity state, these tests use a real
 * {@code WireServer} + {@code WireClient} pair to verify the end-to-end
 * GOAWAY → reconnect flow.
 *
 * @author shenhongxi
 */
class WireGoAwayHandlerTest {

    /**
     * Verify that non-GOAWAY messages pass through the handler untouched.
     * Uses a lightweight stub since WireClient is not needed for pass-through.
     */
    @Test
    void nonGoAwayMessagesPassThrough() {
        // Use a minimal WireClient: we need a URL but no real connection
        // for this test — only the handler's pass-through behaviour
        org.hongxi.jaws.rpc.URL url = new org.hongxi.jaws.rpc.URL(
                "wire", "localhost", 0, "test");
        WireClient client = new WireClient(url);
        EmbeddedChannel ch = new EmbeddedChannel(new WireGoAwayHandler(client));

        String marker = "not-a-goaway";
        ch.writeInbound(marker);
        org.junit.jupiter.api.Assertions.assertEquals(marker, ch.readInbound());
        ch.close();
    }

    /**
     * Verify the connectivity state transition when a GOAWAY is received.
     * The handler transitions READY → IDLE before closing the connection.
     * Since reconnect() will fail (no server running), the state stays IDLE.
     */
    @Test
    void goAwayTransitionsConnectivityState() {
        org.hongxi.jaws.rpc.URL url = new org.hongxi.jaws.rpc.URL(
                "wire", "localhost", 0, "test");
        WireClient client = new WireClient(url);
        WireConnectivityTracker tracker = client.getConnectivityTracker();

        // Simulate the client being in READY state
        tracker.transitionTo(ConnectivityState.CONNECTING);
        tracker.transitionTo(ConnectivityState.READY);
        org.junit.jupiter.api.Assertions.assertEquals(ConnectivityState.READY, tracker.getState());

        EmbeddedChannel ch = new EmbeddedChannel(new WireGoAwayHandler(client));

        // Send a GOAWAY frame (NO_ERROR, lastStreamId=0)
        Http2GoAwayFrame goAway = new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR);
        ch.writeInbound(goAway);

        // Channel should be closed by the handler
        assertFalse(ch.isOpen(), "handler must close the connection on GOAWAY");

        // Connectivity state should have transitioned away from READY
        // (to IDLE, since reconnect fails without a server)
        org.junit.jupiter.api.Assertions.assertNotEquals(ConnectivityState.READY, tracker.getState());
    }

    /**
     * Pins the close/reconnect ordering: the reconnect must be triggered by
     * the close future's completion, never inline while the close is still
     * pending. On a real event loop {@code ctx.close()} is asynchronous, so
     * an inline reconnect races it: {@code reconnect()} sees the channel
     * still active, skips the dial, and proactive recovery silently degrades
     * to a lazy reconnect on the next RPC (regression caught by
     * {@code WireGoAwayDemo} against a grpc-java {@code maxConnectionAge}
     * server). The outbound interceptor below holds the close pending to
     * model that asynchrony on the embedded channel.
     */
    @Test
    void reconnectWaitsForCloseToComplete() {
        org.hongxi.jaws.rpc.URL url = new org.hongxi.jaws.rpc.URL(
                "wire", "localhost", 0, "test");
        AtomicBoolean reconnectCalled = new AtomicBoolean(false);
        WireClient client = new WireClient(url) {
            @Override
            void reconnectOnGoAway() {
                // Record only: no real dial in this test
                reconnectCalled.set(true);
            }
        };

        // Intercept the outbound close and keep it pending until released
        ChannelHandlerContext[] closeCtx = new ChannelHandlerContext[1];
        ChannelPromise[] closePromise = new ChannelPromise[1];
        EmbeddedChannel ch = new EmbeddedChannel(
                new ChannelDuplexHandler() {
                    @Override
                    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                        closeCtx[0] = ctx;
                        closePromise[0] = promise;
                    }
                },
                new WireGoAwayHandler(client));

        ch.writeInbound(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR));

        // Close is still pending: the reconnect must not have run yet
        assertFalse(reconnectCalled.get(),
                "reconnect must wait for the close future, not run inline while close is pending");
        assertTrue(ch.isOpen(), "close must still be pending at this point");

        // Let the close land; its completion must trigger the reconnect
        closeCtx[0].close(closePromise[0]);
        assertTrue(closePromise[0].isDone());
        assertFalse(ch.isOpen());
        assertTrue(reconnectCalled.get(), "close completion must trigger the reconnect");
    }
}
