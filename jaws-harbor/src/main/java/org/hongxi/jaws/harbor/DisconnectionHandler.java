package org.hongxi.jaws.harbor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
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
 *   <li>On {@code channelInactive}, runs the full connection closure
 *       transaction via {@link ConnectionCleanup#cleanup(String)}.</li>
 * </ul>
 * <p>
 * The bi-stream {@code onError}/{@code onCompleted} callbacks trigger the
 * same {@link ConnectionCleanup} closure when the client resets the stream.
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
 * the handler cannot exist without one, hence the {@code channelInactive}
 * closure can never observe a half-built handler with no connection to
 * attribute the teardown to.
 *
 * @see HarborServer
 */
class DisconnectionHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DisconnectionHandler.class);

    private final ConnectionCleanup connectionCleanup;
    private final String connectionId;

    DisconnectionHandler(ConnectionCleanup connectionCleanup, String connectionId) {
        this.connectionCleanup = connectionCleanup;
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
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Use the connectionId directly (not the shared map) to ensure
        // we only remove THIS connection, not another connection from
        // the same clientIp (Nacos client creates multiple connections).
        connectionCleanup.cleanup(connectionId);
        ctx.fireChannelInactive();
    }
}
