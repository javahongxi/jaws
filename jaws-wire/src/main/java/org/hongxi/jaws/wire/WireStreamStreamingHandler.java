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
import org.hongxi.jaws.transport.StreamSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-stream response handler for server-streaming gRPC calls on the client side.
 * <p>
 * Unlike {@link WireStreamResponseHandler} which collects a single response
 * message into a {@link java.util.concurrent.CompletableFuture}, this handler
 * decodes each incoming gRPC frame and feeds it to a
 * {@link StreamSubject}.
 * <p>
 * The handler accumulates DATA frame bytes (guarded by the max-inbound
 * message size), extracts complete gRPC frames via
 * {@link WireFrameCodec#tryExtractFrame(ByteBuf)}, decompresses and decodes
 * each protobuf message, and publishes it. When the trailers HEADERS frame
 * (END_STREAM) arrives, the publisher is completed.
 *
 * @author shenhongxi
 */
class WireStreamStreamingHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WireStreamStreamingHandler.class);

    private final Parser<? extends Message> responseParser;
    private final StreamSubject<Object> observer;
    private final int maxMessageSize;
    /** Max size of inbound HTTP/2 headers (metadata) in bytes; 0 = unlimited. */
    private final int maxInboundMetadataSize;

    private ByteBuf accumulator;
    private int grpcStatus = -1;
    private String grpcMessage;
    /** Decoded grpc-status-details-bin (rich error), or null when the server sent none. */
    private com.google.rpc.Status richStatus;
    private String responseEncoding = WireConstants.ENCODING_IDENTITY;
    /** Observer shared with the {@code ClientCallImpl} of this stream; never {@code null}. */
    private final ClientStreamTracer tracer;
    /** Inbound message counter for this stream, starting at 0. */
    private int inboundMessageNumber;
    /** Guards that {@link StreamTracer#streamClosed(int)} fires exactly once. */
    private boolean streamClosedReported;

    WireStreamStreamingHandler(Parser<? extends Message> responseParser,
                               StreamSubject<Object> observer,
                               int maxMessageSize,
                               int maxInboundMetadataSize,
                               ClientStreamTracer tracer) {
        this.responseParser = responseParser;
        this.observer = observer;
        this.maxMessageSize = maxMessageSize;
        this.maxInboundMetadataSize = maxInboundMetadataSize;
        this.tracer = tracer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                onHeaders(headersFrame);
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else if (msg instanceof Http2ResetFrame resetFrame) {
                reportStreamClosed(WireConstants.STATUS_CANCELED);
                observer.onError(new RuntimeException(
                        "gRPC stream reset: errorCode=" + resetFrame.errorCode()));
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            observer.onError(e);
        }
    }

    private void onHeaders(Http2HeadersFrame headersFrame) {
        // Defense-in-depth: reject oversized inbound metadata
        if (maxInboundMetadataSize > 0 && WireMetadata.estimateHeaderSize(headersFrame.headers()) > maxInboundMetadataSize) {
            reportStreamClosed(WireConstants.STATUS_RESOURCE_EXHAUSTED);
            observer.onError(new RuntimeException(
                    "gRPC response metadata exceeds maxInboundMetadataSize: " + maxInboundMetadataSize));
            return;
        }

        CharSequence statusSeq = headersFrame.headers().get(WireConstants.GRPC_STATUS);
        if (statusSeq != null) {
            grpcStatus = Integer.parseInt(statusSeq.toString());
            CharSequence messageSeq = headersFrame.headers().get(WireConstants.GRPC_MESSAGE);
            if (messageSeq != null) {
                grpcMessage = messageSeq.toString();
            }
            richStatus = WireErrorDetails.fromTrailers(headersFrame.headers());
            tracer.inboundTrailers();
        } else {
            // Initial response HEADERS: capture the response message encoding
            CharSequence encodingSeq = headersFrame.headers().get(WireConstants.GRPC_ENCODING);
            if (encodingSeq != null) {
                responseEncoding = encodingSeq.toString();
            }
            tracer.inboundHeaders();
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

            // Guard against oversized responses: fail the stream and reset
            // instead of buffering unbounded data
            if (accumulator.readableBytes() > maxMessageSize + WireConstants.GRPC_HEADER_SIZE) {
                reportStreamClosed(WireConstants.STATUS_RESOURCE_EXHAUSTED);
                observer.onError(new RuntimeException(
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
                    // Captured before decoding: decode consumes the frame's reader index
                    long wireSize = WireFrameCodec.payloadSize(frame);
                    Message response = WireFrameCodec.decode(frame, responseParser, responseEncoding);
                    tracer.inboundMessageRead(inboundMessageNumber++, wireSize,
                            response.getSerializedSize());
                    observer.onNext(response);
                } catch (Exception e) {
                    log.error("Wire streaming decode failed", e);
                    reportStreamClosed(WireStatus.fromThrowable(e));
                    observer.onError(e);
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
            reportStreamClosed(grpcStatus);
            observer.onError(
                    WireStatus.toException(grpcStatus, grpcMessage, richStatus));
            return;
        }
        reportStreamClosed(WireConstants.STATUS_OK);
        observer.onCompleted();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Closed with no trailers and no reset: the connection went away mid-call
        reportStreamClosed(WireConstants.STATUS_UNKNOWN);
        observer.onError(
                new RuntimeException("gRPC stream closed before completion"));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Wire client streaming error", cause);
        reportStreamClosed(WireStatus.fromThrowable(cause));
        observer.onError(cause);
        ctx.close();
    }

    /** Record the terminal status; only the first reporter of a stream wins. */
    private void reportStreamClosed(int status) {
        if (streamClosedReported) {
            return;
        }
        streamClosedReported = true;
        tracer.streamClosed(status);
    }
}
