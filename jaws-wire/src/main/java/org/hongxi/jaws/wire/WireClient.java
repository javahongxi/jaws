package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamPublisher;
import org.hongxi.jaws.transport.http2.AbstractHttp2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Flow;

/**
 * gRPC client implementation based on Netty HTTP/2. The Netty bootstrap
 * skeleton, optional TLS with ALPN, multi-connection round-robin, lazy
 * reconnection, and lifecycle state are provided by
 * {@link AbstractHttp2Client}; this class implements only the gRPC wire
 * semantics.
 * <p>
 * The request argument must be a protobuf {@link Message} (the first element
 * of {@link Request#getArguments()}). The response is decoded as a protobuf
 * {@link Message} and placed into a {@link DefaultResponse}; custom metadata
 * returned in the gRPC trailers is placed into the response attachments.
 * <p>
 * Request attachments are sent as gRPC metadata (custom HTTP/2 headers), see
 * {@link WireMetadata}. The caller's deadline is propagated via the
 * {@code grpc-timeout} header; a local timeout additionally cancels the
 * stream with RST_STREAM(CANCEL) so the server stops work (gRPC
 * cancellation semantics).
 * <p>
 * Message compression is controlled by the URL parameter {@code compression}
 * ({@code identity} or {@code gzip}); compressed responses are always accepted.
 * Inbound messages larger than {@code maxInboundMessageSize} (default 4MiB,
 * same as grpc-java) fail the call.
 * <p>
 * The response parser must be provided as the {@code responseParser} parameter
 * in {@link #request(Request, Parser)} or {@link #requestStream(Request, Parser)}
 * — gRPC responses cannot be decoded without a target protobuf type.
 *
 * @author shenhongxi
 */
public class WireClient extends AbstractHttp2Client {
    private static final Logger log = LoggerFactory.getLogger(WireClient.class);

    /** Max size of a single inbound gRPC message in bytes. */
    private final int maxMessageSize;
    /** Outbound message compression encoding: identity or gzip. */
    private final String compression;

    public WireClient(URL url) {
        super(url, "WireClient");
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        String compression = url.getParameter(UrlParam.Transport.COMPRESSION);
        if (compression != null && !WireConstants.ENCODING_IDENTITY.equals(compression)
                && !WireCompression.isSupported(compression)) {
            log.warn("Unsupported wire compression '{}', falling back to identity", compression);
            compression = WireConstants.ENCODING_IDENTITY;
        }
        this.compression = compression;
    }

    @Override
    public Response request(Request request) {
        throw new UnsupportedOperationException(
                "WireClient requires a protobuf Parser; use request(Request, Parser) instead");
    }

    /**
     * Send a gRPC request with an explicit response parser.
     * Returns a pending {@link DefaultResponseFuture} immediately; the actual
     * blocking happens in {@code AbstractReference.call()} for the synchronous
     * path, while {@code callAsync()} can chain on the future directly.
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @return a pending response future completed asynchronously by the stream handler
     */
    public Response request(Request request, Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message requestMessage)) {
            throw new JawsServiceException(
                    "WireClient request argument must be a protobuf Message; got: "
                            + (args != null && args.length > 0 ? args[0].getClass().getName() : "null"));
        }

        // Build gRPC path: /{interfaceName}/{methodName}
        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout);

        try {
            io.netty.channel.Channel connChannel = activeChannel();

            // Open a new HTTP/2 stream; the handler completes the future
            // when the gRPC response END_STREAM arrives
            io.netty.channel.Channel streamChannel = new Http2StreamChannelBootstrap(connChannel)
                    .handler(newResponseHandler(responseParser, responseFuture))
                    .open().syncUninterruptibly().getNow();

            // Register callback for timeout + OOM protection; timeout → cancel
            // triggers whenComplete below → RST_STREAM(CANCEL) so the server
            // stops working on it (gRPC cancellation semantics)
            registerCallback(request.getRequestId(), responseFuture);
            responseFuture.whenComplete((r, t) -> {
                if (t == null || ExceptionUtils.isBizException(t)) {
                    resetErrorCount();
                } else {
                    incrErrorCount();
                    cancelStream(streamChannel);
                }
            });

            Http2Headers headers = buildRequestHeaders(request, grpcPath, timeout);
            ByteBuf content = WireFrameCodec.encode(requestMessage, streamChannel.alloc(), compression);
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(content, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            ResponseFuture future = removeCallback(request.getRequestId());
                            if (future != null) {
                                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                                errorResponse.setThrowable(new JawsServiceException(
                                        "Wire stream write failed", f.cause()));
                                future.onFailure(errorResponse);
                            }
                            // incrErrorCount is handled by whenComplete callback above
                        }
                    });
        } catch (Exception e) {
            ResponseFuture future = removeCallback(request.getRequestId());
            if (future != null) {
                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                errorResponse.setThrowable(new JawsServiceException(
                        "WireClient request failed: url=" + url.getUri() + " path=" + grpcPath, e));
                future.onFailure(errorResponse);
            }
            // incrErrorCount is handled by whenComplete callback above
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient request failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }

        // Return the pending future; AbstractReference.call() blocks on
        // getValue() for the sync path, callAsync() chains on it directly
        return responseFuture;
    }

    /**
     * Create the per-stream handler that decodes the gRPC response and
     * completes the {@link DefaultResponseFuture} with a {@link DefaultResponse}
     * wrapping the protobuf message and any trailer metadata.
     */
    private WireStreamResponseHandler newResponseHandler(
            Parser<? extends Message> responseParser, DefaultResponseFuture responseFuture) {
        return new WireStreamResponseHandler(responseParser, responseFuture, maxMessageSize,
                message -> {
                    DefaultResponse response = new DefaultResponse(responseFuture.getRequestId());
                    response.setValue(message);
                    return response;
                },
                () -> removeCallback(responseFuture.getRequestId()));
    }

    /**
     * Send a server-streaming gRPC request and return a {@link Flow.Publisher}
     * that emits each streamed response item. Cancelling the returned
     * subscription sends RST_STREAM(CANCEL) to abort the stream on the server
     * (gRPC cancellation semantics).
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @return a publisher emitting streamed response messages
     */
    public Flow.Publisher<Object> requestStream(Request request, Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message requestMessage)) {
            throw new JawsServiceException(
                    "WireClient requestStream argument must be a protobuf Message; got: "
                            + (args != null && args.length > 0 ? args[0].getClass().getName() : "null"));
        }

        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);

        StreamPublisher publisher = new StreamPublisher();

        try {
            io.netty.channel.Channel connChannel = activeChannel();

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireStreamStreamingHandler(
                                    responseParser, publisher, maxMessageSize))
                            .open().syncUninterruptibly().getNow();

            // Subscriber cancel() → RST_STREAM(CANCEL): the server observes the
            // reset and stops producing (gRPC cancellation semantics)
            publisher.setOnCancel(() -> cancelStream(streamChannel));

            Http2Headers headers = buildRequestHeaders(request, grpcPath, timeout);
            ByteBuf content = WireFrameCodec.encode(requestMessage, streamChannel.alloc(), compression);
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(content, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            publisher.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                            incrErrorCount();
                        }
                    });

            return publisher;
        } catch (Exception e) {
            log.error("Wire streaming request failed: url={} path={}", url.getUri(), grpcPath, e);
            publisher.completeExceptionally(e);
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient requestStream failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }

    /**
     * Build the gRPC request HEADERS: pseudo-headers, content-type, the
     * mandatory {@code te: trailers}, user-agent, encoding advertisement, the
     * caller's deadline, and the request attachments as custom metadata.
     */
    private Http2Headers buildRequestHeaders(Request request, String grpcPath, int timeout) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme(getSslContext() != null ? "https" : "http")
                .path(grpcPath)
                .authority(url.getHostPort())
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS)
                .set(WireConstants.HEADER_USER_AGENT, WireConstants.USER_AGENT)
                // Advertise that compressed responses are accepted
                .set(WireConstants.GRPC_ACCEPT_ENCODING, WireConstants.ACCEPT_ENCODINGS)
                // Propagate the caller's deadline so the server can honor it
                // and report DEADLINE_EXCEEDED (gRPC timeout semantics)
                .set(WireStatus.GRPC_TIMEOUT, WireStatus.encodeTimeout(timeout));
        if (compression != null && !WireConstants.ENCODING_IDENTITY.equals(compression)) {
            headers.set(WireConstants.GRPC_ENCODING, compression);
        }
        // Request attachments → gRPC metadata (custom headers)
        WireMetadata.writeToHeaders(headers, request.getAttachments());
        return headers;
    }

    /**
     * Reset a stream with CANCEL (gRPC call cancellation) and close it.
     * Best-effort: a null or already closed channel is ignored.
     */
    private static void cancelStream(io.netty.channel.Channel streamChannel) {
        if (streamChannel == null || !streamChannel.isActive()) {
            return;
        }
        streamChannel.writeAndFlush(new DefaultHttp2ResetFrame(Http2Error.CANCEL))
                .addListener(f -> streamChannel.close());
    }

    /**
     * Resolve request timeout from dynamic configuration with fallback chain:
     * method-level key -> service-level key -> global key -> URL default.
     */
    private int resolveTimeout(Request request, int urlDefault) {
        String interfaceName = request.getInterfaceName();
        String methodName = request.getMethodName();
        return DynamicConfigurationUtils.resolveIntConfig(urlDefault, v -> v > 0,
                DynamicConfigurationKeys.requestTimeout(interfaceName, methodName),
                DynamicConfigurationKeys.requestTimeout(interfaceName),
                DynamicConfigurationKeys.GLOBAL_REQUEST_TIMEOUT);
    }
}
