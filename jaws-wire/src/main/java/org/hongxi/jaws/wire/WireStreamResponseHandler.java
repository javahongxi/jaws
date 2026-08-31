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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Per-stream response handler for the gRPC client ({@link WireClient}).
 * <p>
 * One instance lives on each stream channel created by {@code WireClient}.
 * It accumulates response DATA frames (guarded by the max-inbound-message
 * size), extracts the gRPC frame via {@link WireFrameCodec} (decompressing
 * per the response's {@code grpc-encoding} header), decodes the protobuf
 * response message, and completes the {@link CompletableFuture} when the
 * trailers HEADERS frame (END_STREAM) arrives. Custom metadata carried in
 * the trailers is collected into an optional map for the caller.
 *
 * @author shenhongxi
 */
class WireStreamResponseHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireStreamResponseHandler.class);

    private final Parser<? extends Message> responseParser;
    private final CompletableFuture<Message> responseFuture;
    private final int maxMessageSize;
    /** Filled with non-reserved trailer metadata when trailers arrive; may be null. */
    private final Map<String, String> trailerMetadata;

    private ByteBuf accumulator;
    private int grpcStatus = -1;
    private String grpcMessage;
    private String responseEncoding = WireConstants.ENCODING_IDENTITY;

    WireStreamResponseHandler(Parser<? extends Message> responseParser,
                              CompletableFuture<Message> responseFuture,
                              int maxMessageSize,
                              Map<String, String> trailerMetadata) {
        this.responseParser = responseParser;
        this.responseFuture = responseFuture;
        this.maxMessageSize = maxMessageSize;
        this.trailerMetadata = trailerMetadata;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                onHeaders(headersFrame);
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else if (msg instanceof Http2ResetFrame resetFrame) {
                responseFuture.completeExceptionally(new RuntimeException(
                        "gRPC stream reset: errorCode=" + resetFrame.errorCode()));
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            responseFuture.completeExceptionally(e);
        }
    }

    private void onHeaders(Http2HeadersFrame headersFrame) {
        // Distinguish initial response HEADERS from trailers HEADERS
        CharSequence statusSeq = headersFrame.headers().get(WireConstants.GRPC_STATUS);
        if (statusSeq != null) {
            // Trailers HEADERS frame carrying grpc-status
            grpcStatus = Integer.parseInt(statusSeq.toString());
            CharSequence messageSeq = headersFrame.headers().get(WireConstants.GRPC_MESSAGE);
            if (messageSeq != null) {
                grpcMessage = messageSeq.toString();
            }
            if (trailerMetadata != null) {
                trailerMetadata.putAll(WireMetadata.fromHeaders(headersFrame.headers()));
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
    }

    private void onData(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
        try {
            ByteBuf content = dataFrame.content();
            if (accumulator == null) {
                accumulator = ctx.alloc().buffer(content.readableBytes());
            }
            accumulator.writeBytes(content);

            // Guard against oversized responses: fail the call and reset the
            // stream instead of buffering unbounded data
            if (accumulator.readableBytes() > maxMessageSize + WireConstants.GRPC_HEADER_SIZE) {
                responseFuture.completeExceptionally(new RuntimeException(
                        "gRPC response exceeds maxInboundMessageSize: " + maxMessageSize));
                ctx.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL));
                ctx.close();
                return;
            }

            if (dataFrame.isEndStream()) {
                // Data arrived with END_STREAM but no trailers yet — unusual for gRPC
                // but handle it gracefully
                completeOrFail();
            }
        } finally {
            dataFrame.release();
        }
    }

    private void completeOrFail() {
        try {
            if (grpcStatus != WireConstants.STATUS_OK && grpcStatus >= 0) {
                // Surface a semantically typed exception: DEADLINE_EXCEEDED carries the
                // jaws timeout error code, UNAVAILABLE is flagged retryable
                responseFuture.completeExceptionally(
                        WireStatus.toException(grpcStatus, grpcMessage));
                return;
            }

            if (accumulator == null || accumulator.readableBytes() == 0) {
                responseFuture.completeExceptionally(
                        new RuntimeException("No response data received"));
                return;
            }

            ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
            if (frame == null) {
                responseFuture.completeExceptionally(
                        new RuntimeException("Incomplete gRPC response frame"));
                return;
            }
            try {
                Message response = WireFrameCodec.decode(frame, responseParser, responseEncoding);
                responseFuture.complete(response);
            } finally {
                frame.release();
            }
        } catch (Exception e) {
            responseFuture.completeExceptionally(e);
        } finally {
            if (accumulator != null && accumulator.refCnt() > 0) {
                accumulator.release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!responseFuture.isDone()) {
            responseFuture.completeExceptionally(
                    new RuntimeException("gRPC stream closed before response received"));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Wire client stream error", cause);
        if (!responseFuture.isDone()) {
            responseFuture.completeExceptionally(cause);
        }
        ctx.close();
    }
}
