package org.hongxi.jaws.harbor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.util.ReferenceCountUtil;
import org.hongxi.jaws.transport.http2.Http2PipelineSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Connection-level handler installed in each HarborServer connection pipeline.
 * <ul>
 *   <li>Consumes incoming GOAWAY frames without closing the connection or
 *       forwarding them. The Nacos client SDK reconnects on any connection
 *       close, so closing here would cause an infinite
 *       connect → GOAWAY → close → reconnect loop.</li>
 *   <li>Treats incoming HTTP/2 PING frames as proof-of-life: calls
 *       {@link ConnectionManager#refreshActiveTime} so the stale-connection watchdog
 *       does not mistake a PING-only connection for a dead one.  Nacos 3.x
 *       clients send both application-level {@code HealthCheckRequest} every
 *       5 s (which already triggers {@code refreshActiveTime} in {@code RequestHandler})
 *       and transport-level gRPC keepalive PINGs every 6 min.  The PING
 *       handling here is a safety net for other gRPC clients that may not
 *       send Payload-level heartbeats.</li>
 *   <li>On {@code channelInactive}, runs the full connection closure
 *       transaction via {@link ConnectionLifecycle#cleanup(String)}.</li>
 * </ul>
 * <p>
 * The bi-stream {@code onError}/{@code onCompleted} callbacks trigger the
 * same {@link ConnectionLifecycle} closure when the client resets the stream.
 * That transaction is idempotent, so the {@code channelInactive} that usually
 * follows a stream error is a no-op; it remains the definitive closure signal
 * when the TCP connection actually closes without a stream event (client
 * disconnect, watchdog, etc.).
 * <p>
 * The connectionId is generated per TCP connection in {@code
 * HarborServer.addOptionalChannelHandlers()} at channel setup and stored as
 * a parent-channel attribute that the wire layer propagates into {@code
 * WireCallContext} for every request on that connection — so the id exists
 * from the moment the connection opens, not only after ServerCheck.  The
 * same id is handed to this handler through its CONSTRUCTOR, so it is final:
 * the handler cannot exist without one, hence neither the PING keep-alive refresh
 * nor the {@code channelInactive} closure can ever observe a half-built handler
 * with no connection to attribute the signal to.
 *
 * @see HarborServer
 */
class ConnectionCleanupHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCleanupHandler.class);

    private final ConnectionLifecycle lifecycle;
    private final String connectionId;

    ConnectionCleanupHandler(ConnectionLifecycle lifecycle, String connectionId) {
        this.lifecycle = lifecycle;
        this.connectionId = Objects.requireNonNull(connectionId,
                "a connection handler without a connectionId could not attribute its own teardown");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2GoAwayFrame) {
            try {
                log.info("[harbor] received GOAWAY from {}",
                        ctx.channel().remoteAddress());
                // Remove the http2_codec handler BEFORE the connection closes.
                // This prevents Http2ConnectionHandler from trying to send a
                // GOAWAY frame back to the client (which would fail with
                // Broken pipe and force-close the connection, triggering
                // the nacos-client to reconnect immediately).  Shared helper
                // because AbstractHttp2Server's exception handler removes the
                // same handler on the reset that follows this GOAWAY.
                Http2PipelineSupport.removeIfExists(ctx.pipeline(),
                        Http2PipelineSupport.HTTP2_CODEC);
            } finally {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
        if (msg instanceof Http2PingFrame ping && !ping.ack()) {
            // Treat each incoming PING as proof-of-life so the stale-connection
            // watchdog does not kill a healthy idle connection.  For Nacos 3.x
            // clients this is redundant (they already send HealthCheckRequest
            // every 5 s which triggers refreshActiveTime in RequestHandler), but it serves
            // as a safety net for other gRPC clients that rely solely on
            // transport-level keepalive PINGs.
            lifecycle.connectionManager().refreshActiveTime(connectionId);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Use the connectionId directly (not the shared map) to ensure
        // we only remove THIS connection, not another connection from
        // the same clientIp (Nacos client creates multiple connections).
        lifecycle.cleanup(connectionId);
        ctx.fireChannelInactive();
    }
}
