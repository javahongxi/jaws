package org.hongxi.jaws.wire;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles HTTP/2 GOAWAY frames on the client connection channel.
 * <p>
 * When the server sends a GOAWAY (e.g. during graceful shutdown or
 * connection recycling), this handler:
 * <ol>
 *   <li>Logs the GOAWAY with the last stream ID and error code</li>
 *   <li>Transitions the connectivity state to IDLE</li>
 *   <li>Closes the current connection</li>
 *   <li>Triggers an immediate reconnect so the next RPC uses a fresh
 *       connection without waiting for a lazy reconnect on the request path</li>
 * </ol>
 * <p>
 * In-flight streams on the old connection will fail and are handled by
 * the retry mechanism ({@link WireRetryPolicy}) if enabled.
 *
 * @author shenhongxi
 */
class WireGoAwayHandler extends ChannelDuplexHandler {
    private static final Logger log = LoggerFactory.getLogger(WireGoAwayHandler.class);

    private final WireClient client;

    WireGoAwayHandler(WireClient client) {
        this.client = client;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2GoAwayFrame goAwayFrame) {
            try {
                log.info("Received GOAWAY from server: lastStreamId={}, errorCode={}, " +
                                "local={}, remote={}; closing connection and reconnecting",
                        goAwayFrame.lastStreamId(), goAwayFrame.errorCode(),
                        ctx.channel().localAddress(), ctx.channel().remoteAddress());

                // Transition connectivity state: READY → IDLE (graceful, not a failure)
                client.getConnectivityTracker().transitionTo(WireConnectivityState.IDLE);

                // Close the connection
                ctx.close();

                // Trigger immediate reconnect so the next RPC doesn't pay
                // the lazy reconnect cost
                client.reconnectOnGoAway();

                // Restore connectivity state after successful reconnect
                if (client.isAvailable()) {
                    client.getConnectivityTracker().transitionTo(WireConnectivityState.READY);
                }
            } finally {
                ReferenceCountUtil.release(goAwayFrame);
            }
            return;
        }
        super.channelRead(ctx, msg);
    }
}
