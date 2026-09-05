package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import org.hongxi.jaws.transport.netty.NettyChannelHandler;
import org.hongxi.jaws.transport.netty.NettyDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First-byte protocol detection handler that sits at the front of the pipeline
 * on an {@link AdaptiveServer} connection. It buffers inbound bytes until the
 * protocol can be determined, then inserts the protocol-specific handlers
 * before itself, replays the buffered data, and removes itself from the
 * pipeline — leaving zero overhead after detection.
 * <p>
 * Detection rules (applied on the first bytes of each TCP connection):
 * <ul>
 *   <li>{@code 0x4A57} — Jaws binary protocol → {@link NettyDecoder} +
 *       {@link NettyChannelHandler}</li>
 *   <li>{@code PRI * HTTP/2.0} — HTTP/2 h2c prior-knowledge →
 *       {@code Http2FrameCodec} + {@link Http2MultiplexHandler}</li>
 *   <li>ASCII HTTP method start ({@code G}ET, {@code P}OST/UT/ATCH,
 *       {@code D}ELETE, {@code H}EAD) — HTTP/1.1 → {@link HttpServerCodec} +
 *       aggregator + {@code HttpRequestHandler}</li>
 * </ul>
 * <p>
 * This handler extends {@link ChannelInboundHandlerAdapter} (not
 * {@link io.netty.handler.codec.ByteToMessageDecoder}) so that the cumulation
 * buffer is managed explicitly and can be safely forwarded to the next
 * pipeline stage before self-removal.
 *
 * @author shenhongxi
 * @see AdaptiveServer
 */
class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    /** Length of the HTTP/2 connection preface ("PRI * HTTP/2.0\r\nSM\r\n\r\n" is 24 bytes). */
    private static final int HTTP2_PREFACE_LENGTH = 24;

    private static final byte[] HTTP2_PREFACE_BYTES = {
            'P', 'R', 'I', ' ', '*', ' ', 'H', 'T', 'T', 'P', '/', '2', '.', '0',
            '\r', '\n', '\r', '\n', 'S', 'M', '\r', '\n', '\r', '\n'
    };

    private final AdaptiveServer adaptiveServer;
    private ByteBuf cumulation;

    ProtocolDetectionHandler(AdaptiveServer adaptiveServer) {
        this.adaptiveServer = adaptiveServer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf data;
        if (msg instanceof ByteBuf buf) {
            data = buf;
        } else {
            // Non-ByteBuf messages shouldn't appear before protocol detection;
            // pass through safely.
            ctx.fireChannelRead(msg);
            return;
        }

        // Append to cumulation buffer
        if (cumulation == null) {
            cumulation = ctx.alloc().buffer(data.readableBytes());
        }
        cumulation.writeBytes(data);
        data.release();

        detectAndConfigure(ctx);
    }

    private void detectAndConfigure(ChannelHandlerContext ctx) {
        if (cumulation.readableBytes() < 3) {
            return; // need at least 3 bytes for initial discrimination
        }

        byte b0 = cumulation.getByte(cumulation.readerIndex());
        byte b1 = cumulation.getByte(cumulation.readerIndex() + 1);

        // Jaws binary: 2-byte magic 0x4A57 ('J' = 0x4A, 'W' = 0x57)
        if (b0 == (byte) 0x4A && b1 == (byte) 0x57) {
            configureJawsBinary(ctx);
            return;
        }

        // HTTP/2 h2c prior-knowledge: starts with 'P', full preface is 24 bytes
        if (b0 == 'P') {
            if (cumulation.readableBytes() < HTTP2_PREFACE_LENGTH) {
                return; // wait for the full 24-byte preface
            }
            if (matchesHttp2Preface()) {
                configureHttp2(ctx);
                return;
            }
        }

        // HTTP/1.1: first byte matches an ASCII HTTP method start character
        if (isHttpMethodStart(b0)) {
            configureHttp1(ctx);
            return;
        }

        // Unknown protocol — fail fast
        throw new IllegalStateException(
                "AdaptiveServer: cannot detect protocol from first bytes: 0x"
                        + Integer.toHexString(b0 & 0xFF) + " 0x" + Integer.toHexString(b1 & 0xFF)
                        + ", remote=" + ctx.channel().remoteAddress());
    }

    private boolean matchesHttp2Preface() {
        int idx = cumulation.readerIndex();
        for (int i = 0; i < HTTP2_PREFACE_LENGTH; i++) {
            if (cumulation.getByte(idx + i) != HTTP2_PREFACE_BYTES[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHttpMethodStart(byte b) {
        // G=GET, P=POST/PUT/PATCH, D=DELETE, H=HEAD, O=OPTIONS, T=TRACE, C=CONNECT
        return b == 'G' || b == 'P' || b == 'D' || b == 'H' || b == 'O' || b == 'T' || b == 'C';
    }

    // ========================================================================
    // Pipeline configuration for each detected protocol
    // ========================================================================

    /**
     * Jaws binary protocol: IdleStateHandler (optional) → HeartbeatHandler (optional)
     * → NettyDecoder → NettyChannelHandler.
     */
    private void configureJawsBinary(ChannelHandlerContext ctx) {
        adaptiveServer.addJawsBinaryPipeline(ctx.pipeline());
        forwardAndCleanup(ctx);
        log.info("AdaptiveServer: detected jaws binary protocol, remote={}", ctx.channel().remoteAddress());
    }

    /**
     * HTTP/2 h2c: Http2FrameCodec → Http2MultiplexHandler with stream routing.
     */
    private void configureHttp2(ChannelHandlerContext ctx) {
        adaptiveServer.addHttp2Pipeline(ctx.pipeline());
        forwardAndCleanup(ctx);
        log.info("AdaptiveServer: detected HTTP/2 h2c, remote={}", ctx.channel().remoteAddress());
    }

    /**
     * HTTP/1.1: HttpServerCodec → HttpObjectAggregator → HttpRequestHandler.
     */
    private void configureHttp1(ChannelHandlerContext ctx) {
        adaptiveServer.addHttp1Pipeline(ctx.pipeline());
        forwardAndCleanup(ctx);
        log.info("AdaptiveServer: detected HTTP/1.1, remote={}", ctx.channel().remoteAddress());
    }

    /**
     * Replay the buffered bytes through the newly configured pipeline, then
     * remove this detection handler.
     * <p>
     * The detector must be removed BEFORE firing the data, otherwise
     * {@code pipeline.fireChannelRead()} (which starts from the pipeline head)
     * would re-enter this handler and trigger a second round of protocol
     * detection, causing {@code IllegalArgumentException: Duplicate handler name}.
     * <p>
     * After self-removal the cumulation reference is nulled so that
     * {@link #handlerRemoved} will not double-release.
     */
    private void forwardAndCleanup(ChannelHandlerContext ctx) {
        // Retain a copy of the buffered data before mutating the pipeline
        ByteBuf retained = cumulation.retainedSlice();
        cumulation.release();
        cumulation = null;

        // Remove the detector FIRST so that fireChannelRead (which starts from
        // the pipeline head) does not re-enter this handler.
        ctx.pipeline().remove(this);

        // Now fire the data through the newly added protocol-specific handlers
        ctx.pipeline().fireChannelRead(retained);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        // Release any remaining cumulation if the handler is removed before
        // detection completes (e.g. connection closed during detection)
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("ProtocolDetectionHandler error: remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
