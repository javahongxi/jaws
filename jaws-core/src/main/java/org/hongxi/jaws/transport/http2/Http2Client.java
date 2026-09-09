package org.hongxi.jaws.transport.http2;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
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
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.StreamPublisher;
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

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout);

        try {
            io.netty.channel.Channel connChannel = activeChannel();

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new Http2StreamResponseHandler(
                                    serialization, this::removeCallback, request.getRequestId()))
                            .open().syncUninterruptibly().getNow();

            // Register before writing so a fast failure (channelInactive) can
            // always find and fail the future
            registerCallback(request.getRequestId(), responseFuture);

            byte[] payload = Http2PayloadCodec.encodeRequest(request, serialization);
            Http2Headers headers = buildRequestHeaders(request);
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(payload), true))
                    .addListener(writeFuture -> {
                        if (writeFuture.isSuccess()) {
                            // Register error fusing only after the write succeeds,
                            // so the write-failure branch below won't double-count.
                            responseFuture.whenComplete((r, t) -> {
                                if (t == null || ExceptionUtils.isBizException(t)) {
                                    resetErrorCount();
                                } else {
                                    incrErrorCount();
                                }
                            });
                        } else {
                            // removeCallback: atomically claim + clean up the map entry,
                            // so the timeout timer won't attempt a duplicate completion
                            ResponseFuture future = removeCallback(request.getRequestId());
                            if (future != null) {
                                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                                errorResponse.setThrowable(new JawsServiceException(
                                        "HTTP/2 stream write failed", writeFuture.cause()));
                                future.onFailure(errorResponse);
                            }
                            incrErrorCount();
                        }
                    });
        } catch (Exception e) {
            // write path failed before/as the callback was registered
            ResponseFuture future = removeCallback(request.getRequestId());
            if (future != null) {
                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                errorResponse.setThrowable(new JawsServiceException("HTTP/2 request error", e));
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

        return responseFuture;
    }

    /**
     * Unified streaming request.  Handles all streaming modes:
     * <ul>
     *   <li>{@code requestStream == null} → server-streaming</li>
     *   <li>{@code requestStream != null} → client/bidi-streaming
     *       (StreamType in HEADERS distinguishes them)</li>
     * </ul>
     *
     * @param request       the RPC request
     * @param requestStream a publisher emitting client request items, or
     *                      {@code null} for server-streaming
     * @return a publisher emitting streamed response items
     */
    @Override
    public Flow.Publisher<Object> requestStream(Request request, Flow.Publisher<Object> requestStream) {
        if (requestStream == null) {
            return doServerStreamRequest(request);
        }
        // Determine stream type from the request's streaming header
        // (set by the caller before invoking this method)
        return doClientOrBidiStreamRequest(request, requestStream);
    }

    /**
     * Server-streaming: send one request, receive a Publisher of response items.
     */
    private Flow.Publisher<Object> doServerStreamRequest(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        StreamPublisher publisher = new StreamPublisher();
        try {
            io.netty.channel.Channel connChannel = activeChannel();

            Http2StreamStreamingHandler streamHandler =
                    new Http2StreamStreamingHandler(serialization, publisher);

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(streamHandler)
                            .open().syncUninterruptibly().getNow();

            // Subscriber cancel() → RST_STREAM: the server observes the
            // reset and stops producing
            publisher.setOnCancel(() -> cancelStream(streamChannel));

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
                    publisher.completeExceptionally(
                            new JawsServiceException("HTTP/2 stream write failed", f.cause()));
                    incrErrorCount();
                    streamChannel.close();
                }
            });

            return publisher;
        } catch (Exception e) {
            log.error("HTTP/2 streaming request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());
            publisher.completeExceptionally(e);
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("Http2Client streaming request failed: url="
                    + url.getUri() + " " + RpcUtils.toString(request), e);
        }
    }

    /**
     * Client/bidi-streaming: send a stream of request items and receive a
     * Publisher of response items.  The wire format (HEADERS streaming mode)
     * is determined by the caller via the request attachments.
     */
    private Flow.Publisher<Object> doClientOrBidiStreamRequest(Request request, Flow.Publisher<Object> requestStream) {
        // Detect stream type from the request's streaming header if present,
        // otherwise default to BIDIRECTIONAL (the common case for non-null requestStream)
        StreamType streamType = StreamType.BIDIRECTIONAL;
        String streamingHeader = request.getAttachments().get(Http2Constants.HEADER_STREAMING);
        if (streamingHeader != null) {
            streamType = StreamType.fromValue(streamingHeader);
        }
        if (streamType == StreamType.CLIENT) {
            return doClientStreamRequest(request, requestStream);
        }
        return doBidiStreamRequest(request, requestStream);
    }

    /**
     * Bidirectional streaming: send request items, receive response items concurrently.
     */
    private Flow.Publisher<Object> doBidiStreamRequest(Request request, Flow.Publisher<Object> requestStream) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        StreamPublisher publisher = new StreamPublisher();
        try {
            io.netty.channel.Channel connChannel = activeChannel();

            Http2StreamStreamingHandler streamHandler =
                    new Http2StreamStreamingHandler(serialization, publisher);

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(streamHandler)
                            .open().syncUninterruptibly().getNow();

            publisher.setOnCancel(() -> cancelStream(streamChannel));

            // Send HEADERS with bidi streaming mode
            Http2Headers headers = buildRequestHeaders(request)
                    .set(Http2Constants.HEADER_STREAMING, StreamType.BIDIRECTIONAL.getValue());
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            log.error("HTTP/2 bidi HEADERS write failed", f.cause());
                            publisher.completeExceptionally(
                                    new JawsServiceException("HTTP/2 bidi HEADERS write failed", f.cause()));
                            incrErrorCount();
                            streamChannel.close();
                        }
                    });

            // Send first DATA frame with Request metadata (no END_STREAM)
            byte[] metadataPayload = Http2PayloadCodec.encodeRequest(request, serialization);
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.wrappedBuffer(metadataPayload), false))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            log.error("HTTP/2 bidi metadata DATA write failed", f.cause());
                            publisher.completeExceptionally(
                                    new JawsServiceException("HTTP/2 bidi metadata write failed", f.cause()));
                            incrErrorCount();
                            streamChannel.close();
                        }
                    });

            // Subscribe to the user's request stream on the shared executor to
            // avoid blocking the caller and potential deadlocks
            streamSubscribeExecutor.execute(() -> requestStream.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription s) {
                    this.subscription = s;
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Object item) {
                    if (!streamChannel.isActive()) {
                        subscription.cancel();
                        return;
                    }
                    try {
                        byte[] itemBytes = Http2StreamCodec.encodeItem(item, serialization);
                        streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                                Unpooled.wrappedBuffer(itemBytes), false))
                                .addListener(f -> {
                                    if (!f.isSuccess()) {
                                        log.error("HTTP/2 bidi stream item write failed", f.cause());
                                        subscription.cancel();
                                        cancelStream(streamChannel);
                                        incrErrorCount();
                                    }
                                });
                    } catch (Exception e) {
                        log.error("Failed to encode bidi stream item", e);
                        subscription.cancel();
                        cancelStream(streamChannel);
                        incrErrorCount();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.error("Client bidi request stream error", throwable);
                    cancelStream(streamChannel);
                    incrErrorCount();
                }

                @Override
                public void onComplete() {
                    // Send END_STREAM to signal request stream complete
                    if (streamChannel.isActive()) {
                        streamChannel.writeAndFlush(new DefaultHttp2DataFrame(true))
                                .addListener(f -> {
                                    if (!f.isSuccess()) {
                                        log.error("HTTP/2 bidi END_STREAM write failed", f.cause());
                                        incrErrorCount();
                                    }
                                });
                    }
                }
            }));

            return publisher;
        } catch (Exception e) {
            log.error("HTTP/2 bidi streaming request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());
            publisher.completeExceptionally(e);
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("Http2Client bidi streaming request failed: url="
                    + url.getUri() + " " + RpcUtils.toString(request), e);
        }
    }

    /**
     * Client-streaming: send request items, receive a single response.
     */
    private Flow.Publisher<Object> doClientStreamRequest(Request request, Flow.Publisher<Object> requestStream) {
        if (!isAvailable()) {
            throw new JawsServiceException("HTTP/2 channel is not available: url="
                    + url.getUri() + RpcUtils.toString(request));
        }

        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        int timeout = resolveTimeout(request, urlTimeout);

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout);
        // Use StreamPublisher (synchronous delivery) instead of SubmissionPublisher
        // to guarantee onNext fires before onComplete on late subscribers.
        StreamPublisher publisher = new StreamPublisher();

        try {
            io.netty.channel.Channel connChannel = activeChannel();

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new Http2StreamResponseHandler(
                                    serialization, this::removeCallback, request.getRequestId()))
                            .open().syncUninterruptibly().getNow();

            // Register before writing so a fast failure can find and fail the future
            registerCallback(request.getRequestId(), responseFuture);

            // Bridge responseFuture → StreamPublisher
            responseFuture.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    publisher.completeExceptionally(throwable);
                } else if (response.getThrowable() != null) {
                    publisher.completeExceptionally(response.getThrowable());
                } else {
                    if (response.getValue() != null) {
                        publisher.addItem(response.getValue());
                    }
                    publisher.complete();
                }
                if (throwable == null || ExceptionUtils.isBizException(throwable)) {
                    resetErrorCount();
                } else {
                    incrErrorCount();
                }
            });

            // Send HEADERS with client streaming mode
            Http2Headers headers = buildRequestHeaders(request)
                    .set(Http2Constants.HEADER_STREAMING, StreamType.CLIENT.getValue());
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            log.error("HTTP/2 client stream HEADERS write failed", f.cause());
                            failClientStream(request.getRequestId(),
                                    new JawsServiceException("HTTP/2 client stream HEADERS write failed", f.cause()));
                            incrErrorCount();
                            streamChannel.close();
                        }
                    });

            // Send first DATA frame with Request metadata (no END_STREAM)
            byte[] metadataPayload = Http2PayloadCodec.encodeRequest(request, serialization);
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.wrappedBuffer(metadataPayload), false))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            log.error("HTTP/2 client stream metadata DATA write failed", f.cause());
                            failClientStream(request.getRequestId(),
                                    new JawsServiceException("HTTP/2 client stream metadata write failed", f.cause()));
                            incrErrorCount();
                            streamChannel.close();
                        }
                    });

            // Subscribe to the user's request stream on the shared executor to
            // avoid blocking the caller and potential deadlocks
            streamSubscribeExecutor.execute(() -> requestStream.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription s) {
                    this.subscription = s;
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Object item) {
                    if (!streamChannel.isActive()) {
                        subscription.cancel();
                        return;
                    }
                    try {
                        byte[] itemBytes = Http2StreamCodec.encodeItem(item, serialization);
                        streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                                Unpooled.wrappedBuffer(itemBytes), false))
                                .addListener(f -> {
                                    if (!f.isSuccess()) {
                                        log.error("HTTP/2 client stream item write failed", f.cause());
                                        subscription.cancel();
                                        cancelStream(streamChannel);
                                        incrErrorCount();
                                    }
                                });
                    } catch (Exception e) {
                        log.error("Failed to encode client stream item", e);
                        subscription.cancel();
                        cancelStream(streamChannel);
                        incrErrorCount();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.error("Client stream request error", throwable);
                    cancelStream(streamChannel);
                    incrErrorCount();
                }

                @Override
                public void onComplete() {
                    // Send END_STREAM to signal request stream complete
                    if (streamChannel.isActive()) {
                        streamChannel.writeAndFlush(new DefaultHttp2DataFrame(true))
                                .addListener(f -> {
                                    if (!f.isSuccess()) {
                                        log.error("HTTP/2 client stream END_STREAM write failed", f.cause());
                                        incrErrorCount();
                                    }
                                });
                    }
                }
            }));

            return publisher;
        } catch (Exception e) {
            log.error("HTTP/2 client streaming request failed: url={} {}, {}", url.getUri(),
                    RpcUtils.toString(request), e.getMessage());
            ResponseFuture future = removeCallback(request.getRequestId());
            if (future != null) {
                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                errorResponse.setThrowable(new JawsServiceException("HTTP/2 client stream request error", e));
                future.onFailure(errorResponse);
            }
            incrErrorCount();
            publisher.completeExceptionally(e);
            return publisher;
        }
    }

    /**
     * Fail the response future for a client-streaming call during the write phase.
     */
    private void failClientStream(long requestId, Exception cause) {
        ResponseFuture future = removeCallback(requestId);
        if (future != null) {
            DefaultResponse errorResponse = new DefaultResponse(requestId);
            errorResponse.setThrowable(cause);
            future.onFailure(errorResponse);
        }
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

        // Propagate streaming mode so the server can route DATA frames
        // to the correct handler (client-streaming / bidi / server-streaming)
        String streaming = request.getAttachments().get(Http2Constants.HEADER_STREAMING);
        if (streaming != null) {
            headers.set(Http2Constants.HEADER_STREAMING, streaming);
        }

        return headers;
    }

    /**
     * Send RST_STREAM(CANCEL) and close the stream channel.
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
