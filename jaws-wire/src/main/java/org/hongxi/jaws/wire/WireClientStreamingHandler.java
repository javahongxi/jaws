package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-stream response handler for server-streaming gRPC calls on the client side.
 * <p>
 * Unlike {@link WireClientStreamHandler} which collects a single response
 * message into a {@link java.util.concurrent.CompletableFuture}, this handler
 * decodes each incoming gRPC frame and feeds it to a
 * {@link StreamingMessagePublisher} that implements {@link java.util.concurrent.Flow.Publisher}.
 * <p>
 * The handler accumulates DATA frame bytes (guarded by the max-inbound
 * message size), extracts complete gRPC frames via
 * {@link WireFrameCodec#tryExtractFrame(ByteBuf)}, decompresses and decodes
 * each protobuf message, and publishes it. When the trailers HEADERS frame
 * (END_STREAM) arrives, the publisher is completed.
 *
 * @author shenhongxi
 */
class WireClientStreamingHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireClientStreamingHandler.class);

    private final Parser<? extends Message> responseParser;
    private final StreamingMessagePublisher publisher;
    private final int maxMessageSize;

    private ByteBuf accumulator;
    private int grpcStatus = -1;
    private String grpcMessage;
    private String responseEncoding = WireConstants.ENCODING_IDENTITY;

    WireClientStreamingHandler(Parser<? extends Message> responseParser,
                               StreamingMessagePublisher publisher,
                               int maxMessageSize) {
        this.responseParser = responseParser;
        this.publisher = publisher;
        this.maxMessageSize = maxMessageSize;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                CharSequence statusSeq = headersFrame.headers().get(WireConstants.GRPC_STATUS);
                if (statusSeq != null) {
                    grpcStatus = Integer.parseInt(statusSeq.toString());
                    CharSequence messageSeq = headersFrame.headers().get(WireConstants.GRPC_MESSAGE);
                    if (messageSeq != null) {
                        grpcMessage = messageSeq.toString();
                    }
                } else {
                    // Initial response HEADERS: capture the response message encoding
                    CharSequence encodingSeq = headersFrame.headers().get(WireConstants.GRPC_ENCODING);
                    if (encodingSeq != null) {
                        responseEncoding = encodingSeq.toString();
                    }
                }
                if (headersFrame.isEndStream()) {
                    completeOrFail();
                }
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else if (msg instanceof Http2ResetFrame resetFrame) {
                publisher.completeExceptionally(new RuntimeException(
                        "gRPC stream reset: errorCode=" + resetFrame.errorCode()));
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            publisher.completeExceptionally(e);
        }
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            ByteBuf content = dataFrame.content();
            if (accumulator == null) {
                accumulator = ctx.alloc().buffer(content.readableBytes());
            }
            accumulator.writeBytes(content);

            // Guard against oversized responses: fail the stream and reset
            // instead of buffering unbounded data
            if (accumulator.readableBytes() > maxMessageSize + WireConstants.GRPC_HEADER_SIZE) {
                publisher.completeExceptionally(new RuntimeException(
                        "gRPC response exceeds maxInboundMessageSize: " + maxMessageSize));
                ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL));
                ctx.close();
                return;
            }

            // Extract and decode all complete gRPC frames from the accumulator
            while (true) {
                ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
                if (frame == null) {
                    break;
                }
                try {
                    Message response = WireFrameCodec.decode(frame, responseParser, responseEncoding);
                    publisher.addItem(response);
                } catch (Exception e) {
                    log.error("Wire streaming decode failed", e);
                    publisher.completeExceptionally(e);
                } finally {
                    frame.release();
                }
            }

            if (dataFrame.isEndStream()) {
                completeOrFail();
            }
        } finally {
            dataFrame.release();
        }
    }

    private void completeOrFail() {
        if (grpcStatus != WireConstants.STATUS_OK && grpcStatus >= 0) {
            // Surface a semantically typed exception: DEADLINE_EXCEEDED carries the
            // jaws timeout error code, UNAVAILABLE is flagged retryable
            publisher.completeExceptionally(
                    WireStatus.toException(grpcStatus, grpcMessage));
            return;
        }
        publisher.complete();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        publisher.completeExceptionally(
                new RuntimeException("gRPC stream closed before completion"));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Wire client streaming error", cause);
        publisher.completeExceptionally(cause);
        ctx.close();
    }
}
