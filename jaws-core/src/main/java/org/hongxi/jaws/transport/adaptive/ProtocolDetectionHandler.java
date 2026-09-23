package org.hongxi.jaws.transport.adaptive;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Protocol detection handler that sits at the front of the pipeline on an
 * {@link AdaptiveServer} connection. It buffers inbound bytes until one of the
 * registered {@link AdaptiveProtocol}s claims the connection, then installs that
 * protocol's handlers, replays the buffered data, and removes itself — leaving
 * zero overhead after detection.
 * <p>
 * The handler holds <em>no protocol knowledge</em>: every magic byte, signature
 * length and pipeline shape lives in the corresponding {@link AdaptiveProtocol}.
 * Its own rules are only about the scan — ask the candidates in order, a
 * {@code MATCH} installs, a {@code NEED_MORE} pauses the whole scan (so an
 * ambiguous prefix is never grabbed by a candidate it would later fall through
 * to), and an unclaimed connection fails fast.
 * <p>
 * This handler extends {@link ChannelInboundHandlerAdapter} (not
 * {@link io.netty.handler.codec.ByteToMessageDecoder}) so that the cumulation
 * buffer is managed explicitly and can be safely forwarded to the next
 * pipeline stage before self-removal.
 *
 * @author shenhongxi
 * @see AdaptiveServer
 * @see AdaptiveProtocol
 */
class ProtocolDetectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ProtocolDetectionHandler.class);

    /**
     * Minimum bytes before the scan starts: the longest window needed to make a
     * first pass over the candidates without a guaranteed {@code NEED_MORE}
     * (TLS 1, jaws magic 2). Signatures longer than this — the h2 preface —
     * request more bytes themselves via {@link AdaptiveProtocol.Result#NEED_MORE}.
     */
    private static final int MIN_DETECTION_BYTES = 3;

    private final List<AdaptiveProtocol> protocols;
    private ByteBuf cumulation;

    ProtocolDetectionHandler(AdaptiveServer adaptiveServer) {
        // Ordered candidates: the first decisive MATCH wins. Order carries meaning
        // only where signatures overlap — TLS and jaws magic are exclusive, the
        // h2 preface must resolve before HTTP/1.1 may claim a leading 'P'.
        this.protocols = List.of(
                new TlsAdaptiveProtocol(adaptiveServer),
                new JawsBinaryAdaptiveProtocol(adaptiveServer),
                new Http2AdaptiveProtocol(adaptiveServer),
                new Http1AdaptiveProtocol(adaptiveServer));
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
        if (cumulation.readableBytes() < MIN_DETECTION_BYTES) {
            return;
        }
        for (AdaptiveProtocol protocol : protocols) {
            AdaptiveProtocol.Result result =
                    protocol.detect(cumulation, ctx.channel().remoteAddress());
            if (result == AdaptiveProtocol.Result.MATCH) {
                protocol.install(ctx.pipeline());
                forwardAndCleanup(ctx);
                log.info("AdaptiveServer: detected {}, remote={}",
                        protocol.name(), ctx.channel().remoteAddress());
                return;
            }
            if (result == AdaptiveProtocol.Result.NEED_MORE) {
                return; // wait for more bytes; do not ask the remaining candidates
            }
        }
        // No candidate claimed the connection — fail fast rather than guess.
        byte b0 = cumulation.getByte(cumulation.readerIndex());
        byte b1 = cumulation.getByte(cumulation.readerIndex() + 1);
        throw new IllegalStateException(
                "AdaptiveServer: cannot detect protocol from first bytes: 0x"
                        + Integer.toHexString(b0 & 0xFF) + " 0x" + Integer.toHexString(b1 & 0xFF)
                        + ", remote=" + ctx.channel().remoteAddress());
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
