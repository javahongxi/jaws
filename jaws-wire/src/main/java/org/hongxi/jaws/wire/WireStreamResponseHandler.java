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
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsServiceException;
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
    /** Max size of inbound HTTP/2 headers (metadata) in bytes; 0 = unlimited. */
    private final int maxInboundMetadataSize;
    /** Wraps a decoded protobuf {@link Message} into a framework {@link DefaultResponse}. */
    private final Function<Message, DefaultResponse> responseBuilder;
    /** Removes the callback from the client's pending map after the future is completed. */
    private final Runnable onCompletion;
    /**
     * What this client can decompress; the response's {@code grpc-encoding} is
     * resolved against it, so a codec the server chose but this side cannot
     * read fails the call instead of mis-parsing the payload.
     */
    private final DecompressorRegistry decompressorRegistry;
    /**
     * When true (default), {@code onCompletion} runs on every completion path
     * (success and failure). When false (retry-enabled calls), it runs only on
     * success; failure paths leave the callback in the map so the retry loop
     * can issue another attempt or remove it on the final failure.
     */
    private final boolean autoRemoveCallback;
    /** Non-reserved trailer metadata collected when the trailers HEADERS frame arrives; may be empty. */
    private Map<String, String> trailerMetadata = Map.of();

    private ByteBuf accumulator;
    private int grpcStatus = -1;
    private String grpcMessage;
    /** Decoded grpc-status-details-bin (rich error), or null when the server sent none. */
    private com.google.rpc.Status richStatus;
    /** Decompressor for the response encoding; identity until headers say otherwise. */
    private Decompressor responseDecompressor = Codec.Identity.NONE;
    /** Observer shared with the {@code ClientCallImpl} of this stream; never {@code null}. */
    private final ClientStreamTracer tracer;
    /** Inbound message counter for this stream, starting at 0. */
    private int inboundMessageNumber;
    /** Guards that {@link StreamTracer#streamClosed(int)} fires exactly once. */
    private boolean streamClosedReported;

    WireStreamResponseHandler(Parser<? extends Message> responseParser,
                              DefaultResponseFuture responseFuture,
                              int maxMessageSize,
                              Function<Message, DefaultResponse> responseBuilder,
                              Runnable onCompletion) {
        this(responseParser, responseFuture, maxMessageSize, 0, responseBuilder, onCompletion,
                true, DecompressorRegistry.getDefaultInstance(), ClientStreamTracer.NOOP);
    }

    WireStreamResponseHandler(Parser<? extends Message> responseParser,
                              DefaultResponseFuture responseFuture,
                              int maxMessageSize,
                              int maxInboundMetadataSize,
                              Function<Message, DefaultResponse> responseBuilder,
                              Runnable onCompletion,
                              boolean autoRemoveCallback,
                              DecompressorRegistry decompressorRegistry,
                              ClientStreamTracer tracer) {
        this.responseParser = responseParser;
        this.responseFuture = responseFuture;
        this.maxMessageSize = maxMessageSize;
        this.maxInboundMetadataSize = maxInboundMetadataSize;
        this.responseBuilder = responseBuilder;
        this.onCompletion = onCompletion;
        this.autoRemoveCallback = autoRemoveCallback;
        this.decompressorRegistry = decompressorRegistry != null
                ? decompressorRegistry : DecompressorRegistry.getDefaultInstance();
        this.tracer = tracer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof Http2HeadersFrame headersFrame) {
                onHeaders(headersFrame);
            } else if (msg instanceof Http2DataFrame dataFrame) {
                onData(ctx, dataFrame);
            } else {
                ReferenceCountUtil.release(msg);
            }
        } catch (Exception e) {
            failCall("gRPC response handling failed", e);
        }
    }

    private void onHeaders(Http2HeadersFrame headersFrame) {
        // Defense-in-depth: reject oversized inbound metadata
        if (maxInboundMetadataSize > 0 && WireMetadata.estimateHeaderSize(headersFrame.headers()) > maxInboundMetadataSize) {
            failCall("gRPC response metadata exceeds maxInboundMetadataSize: "
                    + maxInboundMetadataSize, null);
            return;
        }

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
            // Rich error details (grpc-status-details-bin): decoded and carried onto
            // the thrown exception in completeOrFail (see WireStatusException).
            richStatus = WireErrorDetails.fromTrailers(headersFrame.headers());
            tracer.inboundTrailers();
        } else {
            // Initial response HEADERS: resolve the response message encoding
            // against what this client can actually decompress
            CharSequence encodingSeq = headersFrame.headers().get(WireConstants.GRPC_ENCODING);
            if (encodingSeq != null) {
                String encoding = encodingSeq.toString();
                // identity names the absence of a codec, so it is never a
                // registry lookup: the frame flag alone decides whether bytes
                // need inflating, and a client that registered nothing can
                // still read an uncompressed response
                if (!WireConstants.ENCODING_IDENTITY.equals(encoding)) {
                    Decompressor decompressor = decompressorRegistry.lookupDecompressor(encoding);
                    if (decompressor == null) {
                        failCall("Can't find decompressor for " + encoding
                                + ", the server chose a codec this client cannot read", null);
                        return;
                    }
                    responseDecompressor = decompressor;
                }
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

            // Guard against oversized responses: fail the call and reset the
            // stream instead of buffering unbounded data
            if (accumulator.readableBytes() > maxMessageSize + WireConstants.GRPC_HEADER_SIZE) {
                failCall("gRPC response exceeds maxInboundMessageSize: " + maxMessageSize, null);
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
                errorResponse.setThrowable(WireStatus.toException(grpcStatus, grpcMessage, richStatus));
                // The tracer is finished before the caller is woken, so code that
                // reads what was observed after the call returns never sees a
                // half-recorded stream
                reportStreamClosed(grpcStatus);
                responseFuture.onFailure(errorResponse);
                maybeComplete();
                return;
            }

            if (accumulator == null || accumulator.readableBytes() == 0) {
                failCall("gRPC response carried no message", null);
                return;
            }

            ByteBuf frame = WireFrameCodec.tryExtractFrame(accumulator);
            if (frame == null) {
                failCall("incomplete gRPC response frame", null);
                return;
            }
            try {
                // Captured before decoding: decode consumes the frame's reader index
                long wireSize = WireFrameCodec.payloadSize(frame);
                Message response = WireFrameCodec.decode(frame, responseParser,
                        responseDecompressor);
                tracer.inboundMessageRead(inboundMessageNumber++, wireSize,
                        response.getSerializedSize());
                DefaultResponse successResponse = responseBuilder.apply(response);
                if (!trailerMetadata.isEmpty()) {
                    successResponse.setAttachments(trailerMetadata);
                }
                reportStreamClosed(WireConstants.STATUS_OK);
                responseFuture.onSuccess(successResponse);
            } finally {
                frame.release();
            }
            onCompletion.run();
        } catch (Exception e) {
            failCall("gRPC response could not be decoded", e);
        } finally {
            if (accumulator != null && accumulator.refCnt() > 0) {
                accumulator.release();
            }
        }
    }

    /**
     * Fail this call with an exception the caller can actually catch.
     * <p>
     * The blocking read in {@code DefaultResponseFuture.getValue()} rethrows
     * {@code RuntimeException}s untouched, so completing the future with a raw
     * RuntimeException escapes every {@code JawsAbstractException} handler and
     * leaves the transport's own error-code envelope empty. Everything that ends
     * a call abnormally therefore goes through here, and every message names the
     * request — the exception freezes {@code requestId} from a ThreadLocal that
     * the event loop never populates, so the id only survives inside the text.
     *
     * @param message what happened, without the request id
     * @param cause   the underlying failure, if any
     */
    private void failCall(String message, Throwable cause) {
        if (responseFuture.isDone()) {
            return; // first failure wins; a stream only ends once
        }
        String described = message + ": requestId=" + responseFuture.getRequestId();
        Throwable failure;
        if (cause instanceof JawsAbstractException) {
            failure = cause; // already typed, with its own error code
        } else if (cause != null) {
            failure = new JawsServiceException(described, cause);
        } else {
            failure = new JawsServiceException(described);
        }
        DefaultResponse errorResponse = responseBuilder.apply(null);
        errorResponse.setThrowable(failure);
        responseFuture.onFailure(errorResponse);
        // A call that failed locally never carried a grpc-status, so the
        // tracer gets the code jaws hands the caller
        reportStreamClosed(WireStatus.fromThrowable(failure));
        maybeComplete();
    }

    /** Record the terminal status; only the first reporter of a stream wins. */
    private void reportStreamClosed(int status) {
        if (streamClosedReported) {
            return;
        }
        streamClosedReported = true;
        tracer.streamClosed(status);
    }

    /**
     * An inbound RST_STREAM is the peer's explicit terminal verdict for the
     * call: fail the future immediately with the mapped grpc-style status
     * (CANCEL → CANCELLED, REFUSED_STREAM → UNAVAILABLE, …) instead of burning
     * the whole request timeout. The frame reaches this child pipeline as a
     * user event — {@code Http2MultiplexHandler} forwards it via
     * {@code fireChildUserEventTriggered} (verified end-to-end by
     * WireClientRstObservationTest).
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof Http2ResetFrame reset) {
            int grpcStatus = WireStatus.fromHttp2Error(reset.errorCode());
            failCall("peer reset the stream",
                    WireStatus.toException(grpcStatus, "peer reset the stream"
                            + " (http2 error 0x" + Long.toHexString(reset.errorCode()) + ")"));
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        failCall("gRPC stream closed before the response arrived", null);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Wire client stream error", cause);
        failCall("gRPC stream failed", cause);
        ctx.close();
    }

    /**
     * On failure paths, only run {@code onCompletion} when auto-remove is
     * enabled (non-retry mode). In retry mode the callback stays in the
     * pending map so the retry loop can issue another attempt.
     */
    private void maybeComplete() {
        if (autoRemoveCallback) {
            onCompletion.run();
        }
    }
}
