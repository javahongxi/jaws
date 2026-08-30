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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-stream inbound handler for the HTTP/2 client that doubles as a
 * {@link Flow.Publisher} for server-streaming responses.
 * <p>
 * One instance is created per streaming request opened by {@link Http2Client#requestStream}.
 * It decodes each DATA frame as an independent stream item via {@link Http2StreamCodec}
 * and delivers it to the downstream {@link Flow.Subscriber}. END_STREAM on the response
 * triggers {@link Flow.Subscriber#onComplete()}; stream reset or channel close triggers
 * {@link Flow.Subscriber#onError(Throwable)}.
 * <p>
 * Back-pressure is not propagated over the wire: the server pushes items as
 * they are produced and each DATA frame is delivered to the subscriber on the
 * Netty event loop, so {@code request(n)} below is effectively a no-op hint.
 *
 * @author shenhongxi
 */
class Http2StreamStreamingHandler extends ChannelInboundHandlerAdapter implements Flow.Publisher<Object> {
    private static final Logger log = LoggerFactory.getLogger(Http2StreamStreamingHandler.class);

    private final Serialization serialization;

    private String status;
    private volatile Flow.Subscriber<? super Object> subscriber;
    private final AtomicBoolean terminated = new AtomicBoolean();

    Http2StreamStreamingHandler(Serialization serialization) {
        this.serialization = serialization;
    }

    // ---- Flow.Publisher ------------------------------------------------

    @Override
    public void subscribe(Flow.Subscriber<? super Object> subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("subscriber must not be null");
        }
        this.subscriber = subscriber;
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                // Demand is not propagated over the wire; the server pushes items
                // as they are produced, so this is a no-op hint.
            }

            @Override
            public void cancel() {
                terminate();
            }
        });
    }

    // ---- Netty handler --------------------------------------------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http2HeadersFrame headersFrame) {
            status = Objects.toString(headersFrame.headers().status(), null);
            if (headersFrame.isEndStream()) {
                // Server ended immediately after headers (possibly an error)
                if (!Http2Constants.STATUS_OK.equals(status)) {
                    emitError(new JawsServiceException(
                            "HTTP/2 streaming error: status=" + status));
                }
                complete();
            }
        } else if (msg instanceof Http2DataFrame dataFrame) {
            onData(dataFrame);
        } else if (msg instanceof Http2ResetFrame resetFrame) {
            emitError(new JawsServiceException(
                    "HTTP/2 stream reset: errorCode=" + resetFrame.errorCode()));
        } else {
            ReferenceCountUtil.release(msg);
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
                    emitError(new JawsServiceException(
                            "HTTP/2 streaming error: status=" + status + ", message=" + errorMsg));
                    return;
                }

                try {
                    Object item = Http2StreamCodec.decodeItem(bytes, serialization);
                    deliverItem(item);
                } catch (Exception e) {
                    log.error("Failed to decode stream item", e);
                    emitError(new JawsServiceException("Failed to decode stream item", e));
                }
            }

            if (dataFrame.isEndStream()) {
                complete();
            }
        } finally {
            dataFrame.release();
        }
    }

    private void deliverItem(Object item) {
        Flow.Subscriber<? super Object> sub = this.subscriber;
        if (sub != null && !terminated.get()) {
            try {
                sub.onNext(item);
            } catch (Exception e) {
                log.error("Subscriber onNext threw", e);
                emitError(e);
            }
        }
    }

    private void complete() {
        if (terminated.compareAndSet(false, true)) {
            Flow.Subscriber<? super Object> sub = this.subscriber;
            if (sub != null) {
                try {
                    sub.onComplete();
                } catch (Exception e) {
                    log.error("Subscriber onComplete threw", e);
                }
            }
        }
    }

    private void emitError(Throwable throwable) {
        if (terminated.compareAndSet(false, true)) {
            Flow.Subscriber<? super Object> sub = this.subscriber;
            if (sub != null) {
                try {
                    sub.onError(throwable);
                } catch (Exception e) {
                    log.error("Subscriber onError threw", e);
                }
            }
        }
    }

    private void terminate() {
        terminated.set(true);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!terminated.get()) {
            emitError(new JawsServiceException("HTTP/2 stream closed before streaming completed"));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HTTP/2 streaming client error", cause);
        emitError(cause);
        ctx.close();
    }
}
