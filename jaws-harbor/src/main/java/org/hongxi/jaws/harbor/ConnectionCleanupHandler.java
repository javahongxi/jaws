package org.hongxi.jaws.harbor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection-level handler installed in each HarborServer connection pipeline.
 * <ul>
 *   <li>Consumes incoming GOAWAY frames without closing the connection or
 *       forwarding them. The Nacos client SDK reconnects on any connection
 *       close, so closing here would cause an infinite
 *       connect → GOAWAY → close → reconnect loop.</li>
 *   <li>Treats incoming HTTP/2 PING frames as proof-of-life: calls
 *       {@link ConnectionManager#touch} so the stale-connection watchdog
 *       does not mistake a PING-only connection for a dead one.  Nacos 3.x
 *       clients rely on gRPC keepalive PINGs rather than Payload-level
 *       heartbeats, so without this the watchdog fires after 90 s of idle
 *       and deregisters healthy provider instances.</li>
 *   <li>On {@code channelInactive}, delegates to the {@link HarborServer} to
 *       deregister instances and clean up connection state.</li>
 * </ul>
 * <p>
 * The bi-stream {@code onError}/{@code onCompleted} callbacks handle
 * connection-state cleanup when the client resets the stream. The
 * {@code channelInactive} callback is the definitive cleanup signal when
 * the TCP connection actually closes (client disconnect, watchdog, etc.).
 * <p>
 * The connectionId is set by {@code HarborServer.handleServerCheck()} via the
 * pending-queue mechanism (each new connection enqueues a handler; ServerCheck
 * polls and claims it).
 *
 * @see HarborServer
 */
class ConnectionCleanupHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCleanupHandler.class);

    private final HarborServer server;
    private volatile String connectionId;
    /** The parent (TCP connection) channel, captured in handlerAdded. */
    private Channel parentChannel;

    ConnectionCleanupHandler(HarborServer server) {
        this.server = server;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // This handler is installed on the parent (TCP connection) channel
        // by AbstractHttp2Server.initChannel(). Capture it so that
        // setConnectionId() can store the connectionId as a channel
        // attribute, which the wire layer propagates to WireCallContext.
        this.parentChannel = ctx.channel();
    }

    void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
        // Store on the parent channel so the wire layer can read it
        // and inject into WireCallContext for every subsequent request
        // on this TCP connection.
        if (parentChannel != null) {
            parentChannel.attr(AttributeKey.<String>valueOf(
                    org.hongxi.jaws.wire.WireConstants.CONNECTION_ID)).set(connectionId);
        }
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
                // the nacos-client to reconnect immediately).
                if (ctx.pipeline().get("http2_codec") != null) {
                    ctx.pipeline().remove("http2_codec");
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
        if (msg instanceof Http2PingFrame ping && !ping.ack()) {
            // Nacos 3.x clients keep connections alive via gRPC keepalive
            // PINGs rather than Payload-level heartbeats.  Treat each
            // incoming PING as proof-of-life so the stale-connection
            // watchdog does not kill a healthy idle connection.
            if (connectionId != null) {
                server.getConnectionManager().touch(connectionId);
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Use the connectionId directly (not the shared map) to ensure
        // we only remove THIS connection, not another connection from
        // the same clientIp (Nacos client creates multiple connections).
        if (connectionId != null) {
            server.cleanupConnectionById(connectionId);
        }
        ctx.fireChannelInactive();
    }
}
