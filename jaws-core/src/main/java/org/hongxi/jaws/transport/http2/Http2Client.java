package org.hongxi.jaws.transport.http2;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Flow;

/**
 * HTTP/2-based {@link org.hongxi.jaws.transport.Client} implementation maintaining multiplexed
 * h2c (or h2 over TLS) connections per remote URL. Each request opens its own
 * HTTP/2 stream via {@code Http2StreamChannelBootstrap}, so concurrent requests
 * never contend on application-level framing — the head-of-line blocking present
 * in the request-id multiplexed jaws TCP protocol is eliminated by design.
 * <p>
 * Like {@code NettyClient}, async requests register a {@link ResponseFuture}
 * callback guarded by a one-shot timeout on a shared HashedWheelTimer; the
 * per-stream {@link Http2StreamResponseHandler} completes the future when the
 * response END_STREAM arrives, or fails it on stream reset/close.
 * <p>
 * The Netty bootstrap skeleton, optional TLS with ALPN, multi-connection
 * round-robin, lazy reconnection, and lifecycle state are provided by
 * {@link AbstractHttp2Client}.
 * <p>
 * Gateway-friendly enhancements:
 * <ul>
 *   <li>Mirrors key metadata (interface, method, paramDesc, group, version) into
 *       HTTP/2 HEADERS for gateway-level routing and observability</li>
 *   <li>Optional TLS with ALPN when {@code sslTrustCert} (or mutual TLS cert/key)
 *       is configured</li>
 *   <li>Multi-connection support via {@code connections} parameter to distribute
 *       load across backends behind L4 load balancers</li>
 * </ul>
 *
 * @author shenhongxi
 */
public class Http2Client extends AbstractHttp2Client {
    private static final Logger log = LoggerFactory.getLogger(Http2Client.class);

    private final Serialization serialization;

    public Http2Client(URL url) {
        super(url, "Http2Client");
        this.serialization = Http2PayloadCodec.resolveSerialization(
                url.getParameter(UrlParam.Transport.SERIALIZATION));
    }

    public Serialization getSerialization() {
        return serialization;
    }

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        boolean async = false;
        Object asyncFlag = RpcContext.getContext().getAttribute(JawsConstants.ASYNC_FLAG);
        if (asyncFlag instanceof Boolean b) {
            async = b;
        }

        int urlTimeout = url.getMethodParameter(request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(), UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout, url);

        try {
            io.netty.channel.Channel connChannel = activeChannel();

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new Http2StreamResponseHandler(this, request.getRequestId()))
                            .open().syncUninterruptibly().getNow();

            // Register before writing so a fast failure (channelInactive) can
            // always find and fail the future
            registerCallback(request.getRequestId(), responseFuture);

            byte[] payload = Http2PayloadCodec.encodeRequest(request, serialization);
            Http2Headers headers = buildRequestHeaders(request);
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(payload), true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            ResponseFuture future = removeCallback(request.getRequestId());
                            if (future != null) {
                                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                                errorResponse.setException(new JawsServiceException(
                                        "HTTP/2 stream write failed", f.cause()));
                                future.onFailure(errorResponse);
                            }
                            incrErrorCount();
                        }
                    });

            // Error fusing: reset on success / biz-exception, increment on failure
            responseFuture.addListener(future -> {
                if (future.isSuccess()
                        || (future.isDone() && ExceptionUtils.isBizException(future.getException()))) {
                    resetErrorCount();
                } else {
                    incrErrorCount();
                }
            });
        } catch (Exception e) {
            // write path failed before/as the callback was registered
            ResponseFuture future = removeCallback(request.getRequestId());
            if (future != null) {
                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                errorResponse.setException(new JawsServiceException("HTTP/2 request error", e));
                future.onFailure(errorResponse);
            }
            incrErrorCount();
            log.error("HTTP/2 request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("Http2Client request failed: url="
                    + url.getUri() + " " + RpcUtils.toString(request), e);
        }

        return async ? responseFuture : new DefaultResponse(responseFuture);
    }

    /**
     * Build HTTP/2 HEADERS for a request, including mirrored metadata for
     * gateway visibility.
     */
    private Http2Headers buildRequestHeaders(Request request) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method("POST")
                .scheme(getSslContext() != null ? "https" : "http")
                .path(Http2Constants.PATH)
                .authority(url.getHostPort())
                .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.CONTENT_TYPE)
                .set(Http2Constants.HEADER_SERIALIZATION,
                        url.getParameter(UrlParam.Transport.SERIALIZATION));

        // Mirror metadata for gateway-level routing and observability
        if (request.getInterfaceName() != null) {
            headers.set(Http2Constants.HEADER_INTERFACE, request.getInterfaceName());
        }
        if (request.getMethodName() != null) {
            headers.set(Http2Constants.HEADER_METHOD, request.getMethodName());
        }
        if (request.getParamDesc() != null) {
            headers.set(Http2Constants.HEADER_PARAM_DESC, request.getParamDesc());
        }

        // Mirror group and version from URL parameters if available
        String group = url.getParameter(UrlParam.Identity.GROUP);
        if (group != null && !group.isEmpty()) {
            headers.set(Http2Constants.HEADER_GROUP, group);
        }
        String version = url.getParameter(UrlParam.Identity.VERSION);
        if (version != null && !version.isEmpty()) {
            headers.set(Http2Constants.HEADER_VERSION, version);
        }

        return headers;
    }

    /**
     * Open a server-streaming request: send one request and receive a
     * {@link Flow.Publisher} that emits each response item as it arrives.
     * <p>
     * The caller subscribes to the returned publisher to consume the stream.
     *
     * @param request the RPC request
     * @return a publisher emitting streamed response items
     */
    public Flow.Publisher<Object> requestStream(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        try {
            io.netty.channel.Channel connChannel = activeChannel();

            // Create the streaming handler which doubles as a Flow.Publisher
            Http2StreamClientHandler streamHandler = new Http2StreamClientHandler(serialization);

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(streamHandler)
                            .open().syncUninterruptibly().getNow();

            // Send request headers with streaming mode
            byte[] payload = Http2PayloadCodec.encodeRequest(request, serialization);
            Http2Headers headers = buildRequestHeaders(request)
                    .set(Http2Constants.HEADER_STREAMING, StreamType.SERVER.getValue());
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.wrappedBuffer(payload), true))
                    .addListener(f -> {
                if (!f.isSuccess()) {
                    log.error("HTTP/2 stream write failed for streaming request", f.cause());
                    incrErrorCount();
                    streamChannel.close();
                }
            });

            return streamHandler;
        } catch (Exception e) {
            log.error("HTTP/2 streaming request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("Http2Client streaming request failed: url="
                    + url.getUri() + " " + RpcUtils.toString(request), e);
        }
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
