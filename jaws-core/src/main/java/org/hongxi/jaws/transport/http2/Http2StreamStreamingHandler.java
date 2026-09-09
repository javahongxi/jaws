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
import org.hongxi.jaws.transport.StreamSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Per-stream inbound handler for the HTTP/2 client that decodes each DATA
 * frame as an independent stream item and feeds it to an
 * {@link StreamSubject}.
 * <p>
 * Unlike the previous design where this handler doubled as a
 * publisher logic is now
 * delegated to {@link StreamSubject} for clean separation of concerns:
 * the handler only deals with Netty inbound events and protocol decoding,
 * while the observer manages subscriber lifecycle and buffering.
 * <p>
 * One instance is created per streaming request opened by
 * {@link Http2Client#requestStream}. END_STREAM on the response triggers
 * {@link StreamSubject#onCompleted()}; stream reset or channel close
 * triggers {@link StreamSubject#onError(Throwable)}.
 *
 * @author shenhongxi
 */
class Http2StreamStreamingHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(Http2StreamStreamingHandler.class);

    private final Serialization serialization;
    private final StreamSubject<Object> observer;

    private String status;
    private boolean endStreamReceived;

    Http2StreamStreamingHandler(Serialization serialization, StreamSubject<Object> observer) {
        this.serialization = serialization;
        this.observer = observer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                status = Objects.toString(headersFrame.headers().status(), null);
                if (headersFrame.isEndStream()) {
                    endStreamReceived = true;
                    // Server ended immediately after headers (possibly an error)
                    if (!Http2Constants.STATUS_OK.equals(status)) {
                        observer.onError(new JawsServiceException(
                                "HTTP/2 streaming error: status=" + status));
                        return;
                    }
                    observer.onCompleted();
                }
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(dataFrame);
            } else if (msg instanceof Http2ResetFrame resetFrame) {
                observer.onError(new JawsServiceException(
                        "HTTP/2 stream reset: errorCode=" + resetFrame.errorCode()));
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            observer.onError(e);
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
                    observer.onError(new JawsServiceException(
                            "HTTP/2 streaming error: status=" + status + ", message=" + errorMsg));
                    return;
                }

                try {
                    Object item = Http2StreamCodec.decodeItem(bytes, serialization);
                    observer.onNext(item);
                } catch (Exception e) {
                    log.error("Failed to decode stream item", e);
                    observer.onError(
                            new JawsServiceException("Failed to decode stream item", e));
                }
            }

            if (dataFrame.isEndStream()) {
                endStreamReceived = true;
                observer.onCompleted();
            }
        } finally {
            dataFrame.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Only fail the publisher if the stream was not already completed
        // normally (END_STREAM received). Without this guard, a race between
        // channelInactive and channelRead for the END_STREAM DATA frame could
        // cause completeExceptionally to fire before complete, surfacing a
        // spurious error to the subscriber.
        if (!endStreamReceived) {
            observer.onError(
                    new JawsServiceException("HTTP/2 stream closed before streaming completed"));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP/2 streaming client error", cause);
        observer.onError(cause);
        ctx.close();
    }
}
