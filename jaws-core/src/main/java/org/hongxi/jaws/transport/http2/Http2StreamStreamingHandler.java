package org.hongxi.jaws.transport.http2;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.util.ReferenceCountUtil;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.StreamPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Per-stream inbound handler for the HTTP/2 client that decodes each DATA
 * frame as an independent stream item and feeds it to an
 * {@link StreamPublisher}.
 * <p>
 * Unlike the previous design where this handler doubled as a
 * {@link java.util.concurrent.Flow.Publisher}, the publisher logic is now
 * delegated to {@link StreamPublisher} for clean separation of concerns:
 * the handler only deals with Netty inbound events and protocol decoding,
 * while the publisher manages subscriber lifecycle, buffering, and drain.
 * <p>
 * One instance is created per streaming request opened by
 * {@link Http2Client#requestStream}. END_STREAM on the response triggers
 * {@link StreamPublisher#complete()}; stream reset or channel close
 * triggers {@link StreamPublisher#completeExceptionally(Throwable)}.
 *
 * @author shenhongxi
 */
class Http2StreamStreamingHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Http2StreamStreamingHandler.class);

    private final Serialization serialization;
    private final StreamPublisher publisher;

    private String status;

    Http2StreamStreamingHandler(Serialization serialization, StreamPublisher publisher) {
        this.serialization = serialization;
        this.publisher = publisher;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                status = Objects.toString(headersFrame.headers().status(), null);
                if (headersFrame.isEndStream()) {
                    // Server ended immediately after headers (possibly an error)
                    if (!Http2Constants.STATUS_OK.equals(status)) {
                        publisher.completeExceptionally(new JawsServiceException(
                                "HTTP/2 streaming error: status=" + status));
                        return;
                    }
                    publisher.complete();
                }
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(dataFrame);
            } else if (msg instanceof Http2ResetFrame resetFrame) {
                publisher.completeExceptionally(new JawsServiceException(
                        "HTTP/2 stream reset: errorCode=" + resetFrame.errorCode()));
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            publisher.completeExceptionally(e);
        }
    }

    private void onData(Http2DataFrame dataFrame) {
        try {
            ByteBuf content = dataFrame.content();
            if (content.readableBytes() > 0) {
                byte[] bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);

                if (!Http2Constants.STATUS_OK.equals(status)) {
                    // Error payload — interpret as error message
                    String errorMsg = new String(bytes, StandardCharsets.UTF_8);
                    publisher.completeExceptionally(new JawsServiceException(
                            "HTTP/2 streaming error: status=" + status + ", message=" + errorMsg));
                    return;
                }

                try {
                    Object item = Http2StreamCodec.decodeItem(bytes, serialization);
                    publisher.addItem(item);
                } catch (Exception e) {
                    log.error("Failed to decode stream item", e);
                    publisher.completeExceptionally(
                            new JawsServiceException("Failed to decode stream item", e));
                }
            }

            if (dataFrame.isEndStream()) {
                publisher.complete();
            }
        } finally {
            dataFrame.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        publisher.completeExceptionally(
                new JawsServiceException("HTTP/2 stream closed before streaming completed"));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP/2 streaming client error", cause);
        publisher.completeExceptionally(cause);
        ctx.close();
    }
}
