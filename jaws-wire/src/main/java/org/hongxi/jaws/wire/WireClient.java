package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.http2.AbstractHttp2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client implementation based on Netty HTTP/2. The Netty bootstrap
 * skeleton, optional TLS with ALPN, multi-connection round-robin, lazy
 * reconnection, and lifecycle state are provided by
 * {@link AbstractHttp2Client}; this class implements only the gRPC wire
 * semantics.
 * <p>
 * The request argument must be a protobuf {@link Message} (the first element
 * of {@link Request#getArguments()}). The response is decoded as a protobuf
 * {@link Message} and placed into a {@link DefaultResponse}.
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

    public WireClient(URL url) {
        super(url, "WireClient");
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

        // Build gRPC path: /{interfaceName}/{methodName}
        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        CompletableFuture<Message> resultFuture = new CompletableFuture<>();

        try {
            io.netty.channel.Channel connChannel = activeConnection();

            // Open a new HTTP/2 stream
            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireClientStreamHandler(responseParser, resultFuture))
                            .open().syncUninterruptibly().getNow();

            // Build gRPC request headers
            Http2Headers headers = new DefaultHttp2Headers()
                    .method("POST")
                    .scheme(getSslContext() != null ? "https" : "http")
                    .path(grpcPath)
                    .authority(url.getHostPort())
                    .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                    // Propagate the caller's deadline so the server can honor it
                    // and report DEADLINE_EXCEEDED (gRPC timeout semantics)
                    .set(WireStatus.GRPC_TIMEOUT, WireStatus.encodeTimeout(timeout));

            // Encode request as gRPC frame
            ByteBuf frame = WireFrameCodec.encode(requestMessage, streamChannel.alloc());

            // Send HEADERS + DATA(END_STREAM)
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            resultFuture.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                        }
                    });

            // Wait for response synchronously
            Message responseMessage = resultFuture.get(timeout, TimeUnit.MILLISECONDS);

            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setValue(responseMessage);
            return response;

        } catch (java.util.concurrent.TimeoutException e) {
            throw new JawsServiceException("Wire request timeout: url=" + url.getUri()
                    + " path=" + grpcPath + " timeout=" + timeout + "ms");
        } catch (Exception e) {
            log.error("Wire request failed: url={} path={}", url.getUri(), grpcPath, e);
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient request failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
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

        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();
        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        CompletableFuture<Message> resultFuture = new CompletableFuture<>();

        try {
            io.netty.channel.Channel connChannel = activeConnection();

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireClientStreamHandler(responseParser, resultFuture))
                            .open().syncUninterruptibly().getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                    .method("POST")
                    .scheme(getSslContext() != null ? "https" : "http")
                    .path(grpcPath)
                    .authority(url.getHostPort())
                    .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                    // Propagate the caller's deadline so the server can honor it
                    // and report DEADLINE_EXCEEDED (gRPC timeout semantics)
                    .set(WireStatus.GRPC_TIMEOUT, WireStatus.encodeTimeout(timeout));

            // Wrap raw protobuf bytes into a gRPC frame
            ByteBuf frame = WireFrameCodec.encodeRawBytes(rawBytes, streamChannel.alloc());

            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            resultFuture.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                        }
                    });

            Message responseMessage = resultFuture.get(timeout, TimeUnit.MILLISECONDS);

            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setValue(responseMessage);
            return response;

        } catch (java.util.concurrent.TimeoutException e) {
            throw new JawsServiceException("Wire request timeout: url=" + url.getUri()
                    + " path=" + grpcPath + " timeout=" + timeout + "ms");
        } catch (Exception e) {
            log.error("Wire request failed: url={} path={}", url.getUri(), grpcPath, e);
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
     * each streamed response item.
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

        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        StreamingMessagePublisher publisher = new StreamingMessagePublisher();

        try {
            io.netty.channel.Channel connChannel = activeConnection();

            // Open a new HTTP/2 stream with the streaming handler
            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireClientStreamingHandler(responseParser, publisher))
                            .open().syncUninterruptibly().getNow();

            // Build gRPC request headers
            Http2Headers headers = new DefaultHttp2Headers()
                    .method("POST")
                    .scheme(getSslContext() != null ? "https" : "http")
                    .path(grpcPath)
                    .authority(url.getHostPort())
                    .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                    // Propagate the caller's deadline so the server can honor it
                    // and report DEADLINE_EXCEEDED (gRPC timeout semantics)
                    .set(WireStatus.GRPC_TIMEOUT, WireStatus.encodeTimeout(timeout));

            // Encode request as gRPC frame
            ByteBuf frame = WireFrameCodec.encode(requestMessage, streamChannel.alloc());

            // Send HEADERS + DATA(END_STREAM)
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            publisher.completeExceptionally(
                                    new JawsServiceException("Wire stream write failed", f.cause()));
                        }
                    });

            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setValue(publisher);
            return response;

        } catch (Exception e) {
            log.error("Wire streaming request failed: url={} path={}", url.getUri(), grpcPath, e);
            publisher.completeExceptionally(e);
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient requestStream failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }
}
