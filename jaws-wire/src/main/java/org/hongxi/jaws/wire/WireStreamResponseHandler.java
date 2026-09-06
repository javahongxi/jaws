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
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * Per-stream response handler for the gRPC client ({@link WireClient}).
 * <p>
 * One instance lives on each stream channel created by {@code WireClient}.
 * It accumulates response DATA frames (guarded by the max-inbound-message
 * size), extracts the gRPC frame via {@link WireFrameCodec} (decompressing
 * per the response's {@code grpc-encoding} header), decodes the protobuf
 * response message, and completes the {@link DefaultResponseFuture} when the
 * trailers HEADERS frame (END_STREAM) arrives. Custom metadata carried in
 * the trailers is collected and set as response attachments so that the
 * caller receives a framework-level {@code Response} object.
 *
 * @author shenhongxi
 */
class WireStreamResponseHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireStreamResponseHandler.class);

    private final Parser<? extends Message> responseParser;
    private final DefaultResponseFuture responseFuture;
    private final int maxMessageSize;
    /** Wraps a decoded protobuf {@link Message} into a framework {@link DefaultResponse}. */
    private final Function<Message, DefaultResponse> responseBuilder;
    /** Removes the callback from the client's pending map after the future is completed. */
    private final Runnable onCompletion;
    /** Non-reserved trailer metadata collected when the trailers HEADERS frame arrives; may be empty. */
    private Map<String, String> trailerMetadata = Map.of();

    private ByteBuf accumulator;
    private int grpcStatus = -1;
    private String grpcMessage;
    private String responseEncoding = WireConstants.ENCODING_IDENTITY;

    WireStreamResponseHandler(Parser<? extends Message> responseParser,
                              DefaultResponseFuture responseFuture,
                              int maxMessageSize,
                              Function<Message, DefaultResponse> responseBuilder,
                              Runnable onCompletion) {
        this.responseParser = responseParser;
        this.responseFuture = responseFuture;
        this.maxMessageSize = maxMessageSize;
        this.responseBuilder = responseBuilder;
        this.onCompletion = onCompletion;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                onHeaders(headersFrame);
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else if (msg instanceof Http2ResetFrame resetFrame) {
                DefaultResponse errorResponse = responseBuilder.apply(null);
                errorResponse.setThrowable(new RuntimeException(
                        "gRPC stream reset: errorCode=" + resetFrame.errorCode()));
                responseFuture.onFailure(errorResponse);
                onCompletion.run();
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            DefaultResponse errorResponse = responseBuilder.apply(null);
            errorResponse.setThrowable(e);
            responseFuture.onFailure(errorResponse);
            onCompletion.run();
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
            trailerMetadata = WireMetadata.fromHeaders(headersFrame.headers());
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
                DefaultResponse errorResponse = responseBuilder.apply(null);
                errorResponse.setThrowable(new RuntimeException(
                        "gRPC response exceeds maxInboundMessageSize: " + maxMessageSize));
                responseFuture.onFailure(errorResponse);
                onCompletion.run();
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
                DefaultResponse errorResponse = responseBuilder.apply(null);
                errorResponse.setThrowable(WireStatus.toException(grpcStatus, grpcMessage));
                responseFuture.onFailure(errorResponse);
                onCompletion.run();
                return;
            }

            if (accumulator == null || accumulator.readableBytes() == 0) {
                DefaultResponse errorResponse = responseBuilder.apply(null);
                errorResponse.setThrowable(new RuntimeException("No response data received"));
                responseFuture.onFailure(errorResponse);
                onCompletion.run();
                return;
            }

            ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
            if (frame == null) {
                DefaultResponse errorResponse = responseBuilder.apply(null);
                errorResponse.setThrowable(new RuntimeException("Incomplete gRPC response frame"));
                responseFuture.onFailure(errorResponse);
                onCompletion.run();
                return;
            }
            try {
                Message response = WireFrameCodec.decode(frame, responseParser, responseEncoding);
                DefaultResponse successResponse = responseBuilder.apply(response);
                if (!trailerMetadata.isEmpty()) {
                    successResponse.setAttachments(trailerMetadata);
                }
                responseFuture.onSuccess(successResponse);
            } finally {
                frame.release();
            }
            onCompletion.run();
        } catch (Exception e) {
            DefaultResponse errorResponse = responseBuilder.apply(null);
            errorResponse.setThrowable(e);
            responseFuture.onFailure(errorResponse);
            onCompletion.run();
        } finally {
            if (accumulator != null && accumulator.refCnt() > 0) {
                accumulator.release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!responseFuture.isDone()) {
            DefaultResponse errorResponse = responseBuilder.apply(null);
            errorResponse.setThrowable(new RuntimeException(
                    "gRPC stream closed before response received"));
            responseFuture.onFailure(errorResponse);
            onCompletion.run();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Wire client stream error", cause);
        if (!responseFuture.isDone()) {
            DefaultResponse errorResponse = responseBuilder.apply(null);
            errorResponse.setThrowable(cause);
            responseFuture.onFailure(errorResponse);
            onCompletion.run();
        }
        ctx.close();
    }
}
