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
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.http2.AbstractHttp2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
 * The response parser is provided via the {@code responseParser} parameter
 * in the {@link #request(Request, Parser)} method, or via the URL parameter
 * {@code wireResponseParser} for the standard {@link #request(Request)} path.
 *
 * @author shenhongxi
 */
public class WireClient extends AbstractHttp2Client {
    private static final Logger log = LoggerFactory.getLogger(WireClient.class);

    /**
     * The response parser for decoding gRPC responses. Must be set before
     * calling {@link #request(Request)} via {@link #setResponseParser(Parser)}.
     */
    private volatile Parser<? extends Message> responseParser;

    /** Max size of a single inbound gRPC message in bytes. */
    private final int maxMessageSize;
    /** Outbound message compression encoding: identity or gzip. */
    private final String compression;

    public WireClient(URL url) {
        super(url, "WireClient");
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        String configured = url.getParameter(UrlParam.Transport.COMPRESSION);
        if (configured != null
                && !WireConstants.ENCODING_IDENTITY.equals(configured)
                && !WireCompression.isSupported(configured)) {
            log.warn("Unsupported wire compression '{}', falling back to identity", configured);
            configured = WireConstants.ENCODING_IDENTITY;
        }
        this.compression = configured;
    }

    /**
     * Set the protobuf parser used to decode response messages.
     * Must be called before {@link #request(Request)}.
     */
    public void setResponseParser(Parser<? extends Message> responseParser) {
        this.responseParser = responseParser;
    }

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        Parser<? extends Message> parser = this.responseParser;
        if (parser == null) {
            throw new JawsServiceException(
                    "WireClient responseParser not set; call setResponseParser() before request()");
        }

        return request(request, parser);
    }

    /**
     * Send a gRPC request with an explicit response parser.
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @return the response containing the decoded protobuf message
     */
    public Response request(Request request, Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message)) {
            throw new JawsServiceException(
                    "WireClient request argument must be a protobuf Message; got: "
                            + (args != null && args.length > 0 ? args[0].getClass().getName() : "null"));
        }
        Message requestMessage = (Message) args[0];
        return doUnaryCall(request, responseParser,
                alloc -> WireFrameCodec.encode(requestMessage, alloc, compression));
    }

    /**
     * Send raw protobuf bytes as a gRPC frame. Used by the SPI protocol layer
     * where arguments are opaque bytes rather than typed {@link Message} instances.
     *
     * @param request     the RPC request (interface/method used for gRPC path)
     * @param rawBytes    raw protobuf bytes (without the 5-byte gRPC header)
     * @param responseParser the parser for decoding the response message
     * @return the response containing the decoded protobuf message
     */
    public Response sendRawBytes(Request request, byte[] rawBytes,
                                 Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }
        return doUnaryCall(request, responseParser,
                alloc -> WireFrameCodec.encodeRawBytes(rawBytes, alloc, compression));
    }

    /**
     * Shared unary call flow: open a stream, send HEADERS + DATA(END_STREAM),
     * and wait for the response within the request timeout. The request frame
     * is encoded with the stream channel's allocator right before writing.
     */
    private Response doUnaryCall(Request request, Parser<? extends Message> responseParser,
                                Function<io.netty.buffer.ByteBufAllocator, ByteBuf> frameEncoder) {
        // Build gRPC path: /{interfaceName}/{methodName}
        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);

        CompletableFuture<Message> resultFuture = new CompletableFuture<>();
        Map<String, String> trailerMetadata = new HashMap<>();
        io.netty.channel.Channel streamChannel = null;

        try {
            io.netty.channel.Channel connChannel = activeConnection();

            // Open a new HTTP/2 stream
            streamChannel = new Http2StreamChannelBootstrap(connChannel)
                    .handler(new WireClientStreamHandler(responseParser, resultFuture,
                            maxMessageSize, trailerMetadata))
                    .open().syncUninterruptibly().getNow();

            ByteBuf frame = frameEncoder.apply(streamChannel.alloc());
            streamChannel.write(new DefaultHttp2HeadersFrame(buildRequestHeaders(request, grpcPath, timeout)));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            resultFuture.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                            incrErrorCount();
                        }
                    });

            // Wait for response synchronously
            Message responseMessage = resultFuture.get(timeout, TimeUnit.MILLISECONDS);

            // Success — reset the error fusing counter
            resetErrorCount();

            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setValue(responseMessage);
            if (!trailerMetadata.isEmpty()) {
                response.setAttachments(trailerMetadata);
            }
            return response;

        } catch (java.util.concurrent.TimeoutException e) {
            // Cancel the call: RST_STREAM(CANCEL) tells the server to stop
            // working on it (gRPC cancellation semantics)
            cancelStream(streamChannel);
            incrErrorCount();
            throw new JawsServiceException("Wire request timeout: url=" + url.getUri()
                    + " path=" + grpcPath + " timeout=" + timeout + "ms");
        } catch (Exception e) {
            log.error("Wire request failed: url={} path={}", url.getUri(), grpcPath, e);
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient request failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }

    /**
     * Send a server-streaming gRPC request and return a {@link Response} whose
     * value is a {@link Flow.Publisher Publisher&lt;Message&gt;} that emits
     * each streamed response item. Cancelling the returned subscription sends
     * RST_STREAM(CANCEL) to abort the stream on the server.
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @return the response containing a streaming publisher
     */
    public Response requestStream(Request request, Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message)) {
            throw new JawsServiceException(
                    "WireClient requestStream argument must be a protobuf Message; got: "
                            + (args != null && args.length > 0 ? args[0].getClass().getName() : "null"));
        }
        Message requestMessage = (Message) args[0];

        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);

        StreamingMessagePublisher publisher = new StreamingMessagePublisher();

        try {
            io.netty.channel.Channel connChannel = activeConnection();

            // Open a new HTTP/2 stream with the streaming handler
            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireClientStreamingHandler(responseParser, publisher, maxMessageSize))
                            .open().syncUninterruptibly().getNow();

            // Subscriber cancel() → RST_STREAM(CANCEL): the server observes the
            // reset and stops producing (gRPC cancellation semantics)
            publisher.setCancelAction(() -> cancelStream(streamChannel));

            // Encode request as gRPC frame
            ByteBuf frame = WireFrameCodec.encode(requestMessage, streamChannel.alloc(), compression);

            // Send HEADERS + DATA(END_STREAM)
            streamChannel.write(new DefaultHttp2HeadersFrame(buildRequestHeaders(request, grpcPath, timeout)));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            publisher.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                            incrErrorCount();
                        }
                    });

            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setValue(publisher);
            return response;

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
