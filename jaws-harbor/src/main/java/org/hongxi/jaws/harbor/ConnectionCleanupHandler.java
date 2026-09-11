package org.hongxi.jaws.harbor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection-level handler installed in each HarborServer connection pipeline.
 * <ul>
 *   <li>Responds to incoming GOAWAY by closing the connection channel, so that
 *       a graceful client shutdown is detected immediately instead of waiting
 *       for the 90-second watchdog.</li>
 *   <li>On {@code channelInactive}, delegates to the {@link HarborServer} to
 *       deregister instances and clean up connection state.</li>
 * </ul>
 * The clientIp is set by {@code HarborServer.handleServerCheck()} via the
 * pending-queue mechanism (each new connection enqueues a handler; ServerCheck
 * polls and claims it).
 *
 * @see HarborServer
 */
class ConnectionCleanupHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCleanupHandler.class);

    private final HarborServer server;
    private volatile String clientIp;

    ConnectionCleanupHandler(HarborServer server) {
        this.server = server;
    }

    void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2GoAwayFrame) {
            try {
                log.info("[harbor] received GOAWAY from {}",
                        ctx.channel().remoteAddress());
                // Do NOT close the connection here. The nacos-client SDK
                // reconnects on any connection close, causing an infinite
                // loop (connect → GOAWAY → close → reconnect → ...).
                // Let the bi-stream onError/onCompleted or the 90-second
                // watchdog handle cleanup naturally.
            } finally {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (clientIp != null) {
            server.cleanupConnectionByClientIp(clientIp);
        }
        ctx.fireChannelInactive();
    }
}
