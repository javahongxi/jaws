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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** Max size of inbound HTTP/2 headers (metadata) in bytes. */
    private final int maxInboundMetadataSize;
    /** Outbound message compression encoding: identity or gzip. */
    private final String compression;
    /** Client keepalive interval; 0 means disabled. */
    private final long keepaliveTimeMs;
    /** Client keepalive ACK timeout. */
    private final long keepaliveTimeoutMs;
    /** Retry policy; null when retries are disabled (maxAttempts ≤ 1). */
    private final WireRetryPolicy retryPolicy;
    /** DNS service discovery resolver; null when DNS is disabled. */
    private volatile WireDnsResolver dnsResolver;
    /** gRPC connectivity state tracker. */
    private final WireConnectivityTracker connectivityTracker = new WireConnectivityTracker();
    /** Shared scheduler for keepalive PINGs and retry backoff across connections. */
    private static final ScheduledExecutorService KEEPALIVE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wire-keepalive");
                t.setDaemon(true);
                return t;
            });
    /** Dedicated scheduler for retry backoff delays. */
    private static final ScheduledExecutorService RETRY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wire-retry");
                t.setDaemon(true);
                return t;
            });

    public WireClient(URL url) {
        super(url, "WireClient");
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        this.maxInboundMetadataSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_METADATA_SIZE);
        String compression = url.getParameter(UrlParam.Transport.COMPRESSION);
        if (compression != null && !WireConstants.ENCODING_IDENTITY.equals(compression)
                && !WireCompression.isSupported(compression)) {
            log.warn("Unsupported wire compression '{}', falling back to identity", compression);
            compression = WireConstants.ENCODING_IDENTITY;
        }
        this.compression = compression;
        this.keepaliveTimeMs = url.getLongParameter(UrlParam.Transport.KEEPALIVE_TIME_MS);
        this.keepaliveTimeoutMs = url.getLongParameter(UrlParam.Transport.KEEPALIVE_TIMEOUT_MS);
        this.retryPolicy = WireRetryPolicy.fromUrl(url);

        // DNS service discovery: resolve hostname to multiple addresses
        boolean dnsEnabled = url.getBoolParameter(UrlParam.Transport.DNS_ENABLED);
        if (dnsEnabled) {
            long refreshInterval = url.getLongParameter(UrlParam.Transport.DNS_REFRESH_INTERVAL_MS);
            dnsResolver = new WireDnsResolver(url.getHost(), url.getPort(), refreshInterval);
        }
    }

    @Override
    protected void addOptionalChannelHandlers(io.netty.channel.ChannelPipeline pipeline) {
        // GOAWAY: server-initiated connection closure → reconnect immediately
        pipeline.addLast("wire_goaway", new WireGoAwayHandler(this));
        if (keepaliveTimeMs > 0) {
            pipeline.addLast("wire_keepalive", new WireClientKeepaliveHandler(
                    keepaliveTimeMs, keepaliveTimeoutMs, KEEPALIVE_SCHEDULER));
        }
    }

    /**
     * Start the DNS resolver (if enabled) after the base class opens
     * the connection. The resolver periodically re-resolves the hostname
     * and logs address changes.
     */
    @Override
    public synchronized boolean open() {
        connectivityTracker.transitionTo(WireConnectivityState.CONNECTING);
        boolean opened;
        try {
            opened = super.open();
        } catch (Exception e) {
            connectivityTracker.transitionTo(WireConnectivityState.TRANSIENT_FAILURE);
            throw e;
        }
        if (opened) {
            connectivityTracker.transitionTo(WireConnectivityState.READY);
            if (dnsResolver != null) {
                dnsResolver.start(new WireDnsResolver.Listener() {
                    @Override
                    public void onAddresses(List<InetSocketAddress> addresses) {
                        log.info("DNS update for WireClient({}): {} resolved addresses",
                                url.getHost(), addresses.size());
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.warn("DNS resolution error for WireClient({}): {}",
                                url.getHost(), error.getMessage());
                    }
                });
            }
        } else {
            connectivityTracker.transitionTo(WireConnectivityState.TRANSIENT_FAILURE);
        }
        return opened;
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

        if (retryPolicy != null) {
            // Retry-enabled path: the callback stays in the map across attempts
            // and is only removed on success or final failure
            registerCallback(request.getRequestId(), responseFuture);
            attemptRequest(request, responseParser, requestMessage, grpcPath, timeout,
                    responseFuture, new AtomicInteger(0));
        } else {
            // Non-retry path: original behaviour
            doSingleAttempt(request, responseParser, requestMessage, grpcPath, timeout,
                    responseFuture, true);
        }

        return responseFuture;
    }

    /**
     * Execute a single request attempt with optional retry on failure.
     * On retryable failure the next attempt is scheduled with exponential
     * backoff; on non-retryable failure or exhausted attempts the callback
     * is removed and the future remains failed.
     * <p>
     * Retry coordination: each attempt registers a {@code whenComplete}
     * callback on the shared future. An {@link AtomicInteger} counter
     * ensures that at most one callback acts per completion event:
     * the first callback to CAS from its attempt number to attempt+1
     * wins the right to schedule the next retry; all others see a
     * stale value and no-op.
     */
    private void attemptRequest(Request request, Parser<? extends Message> responseParser,
                                Message requestMessage, String grpcPath, int timeout,
                                DefaultResponseFuture responseFuture, AtomicInteger attemptCounter) {
        int attempt = attemptCounter.get();
        try {
            io.netty.channel.Channel connChannel = activeChannel();

            WireStreamResponseHandler handler = new WireStreamResponseHandler(
                    responseParser, responseFuture, maxMessageSize, maxInboundMetadataSize,
                    message -> {
                        DefaultResponse response = new DefaultResponse(responseFuture.getRequestId());
                        response.setValue(message);
                        return response;
                    },
                    () -> removeCallback(responseFuture.getRequestId()),
                    false /* retry loop manages the callback */);

            io.netty.channel.Channel streamChannel = new Http2StreamChannelBootstrap(connChannel)
                    .handler(handler)
                    .open().syncUninterruptibly().getNow();

            // Retry-coordinated callback: only one callback per completion
            // wins the CAS race to schedule the next attempt
            responseFuture.whenComplete((r, t) -> {
                if (t == null || ExceptionUtils.isBizException(t)) {
                    resetErrorCount();
                    removeCallback(responseFuture.getRequestId());
                    return;
                }
                incrErrorCount();
                cancelStream(streamChannel);

                // CAS: only the first callback per completion event acts
                if (attemptCounter.compareAndSet(attempt, attempt + 1)
                        && retryPolicy.hasAnotherAttempt(attempt)
                        && WireRetryPolicy.isRetryableFailure(t)) {
                    long delay = retryPolicy.backoffDelayMs(attempt);
                    log.info("gRPC retry: attempt {}/{}, backing off {}ms for path={}",
                            attempt + 1, retryPolicy.maxAttempts(), delay, grpcPath);
                    RETRY_SCHEDULER.schedule(() ->
                            attemptRequest(request, responseParser, requestMessage,
                                    grpcPath, timeout, responseFuture, attemptCounter),
                            delay, TimeUnit.MILLISECONDS);
                } else if (!retryPolicy.hasAnotherAttempt(attempt)
                        || !WireRetryPolicy.isRetryableFailure(t)) {
                    // Non-retryable or exhausted: clean up
                    removeCallback(responseFuture.getRequestId());
                }
                // else: another callback already claimed the retry
            });

            Http2Headers headers = buildRequestHeaders(request, grpcPath, timeout);
            ByteBuf content = WireFrameCodec.encode(requestMessage, streamChannel.alloc(), compression);
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(content, true))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                            errorResponse.setThrowable(new JawsServiceException(
                                    "Wire stream write failed", f.cause()));
                            responseFuture.onFailure(errorResponse);
                        }
                    });
        } catch (Exception e) {
            if (attemptCounter.compareAndSet(attempt, attempt + 1)
                    && retryPolicy.hasAnotherAttempt(attempt)
                    && WireRetryPolicy.isRetryableFailure(e)) {
                long delay = retryPolicy.backoffDelayMs(attempt);
                log.info("gRPC retry: attempt {}/{}, backing off {}ms for path={}",
                        attempt + 1, retryPolicy.maxAttempts(), delay, grpcPath);
                RETRY_SCHEDULER.schedule(() ->
                        attemptRequest(request, responseParser, requestMessage,
                                grpcPath, timeout, responseFuture, attemptCounter),
                        delay, TimeUnit.MILLISECONDS);
            } else {
                removeCallback(request.getRequestId());
                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                errorResponse.setThrowable(new JawsServiceException(
                        "WireClient request failed: url=" + url.getUri() + " path=" + grpcPath, e));
                responseFuture.onFailure(errorResponse);
                if (e instanceof JawsAbstractException jae) {
                    throw jae;
                }
                throw new JawsServiceException("WireClient request failed: url="
                        + url.getUri() + " path=" + grpcPath, e);
            }
        }
    }

    /**
     * Execute a single request attempt without retry logic (original path).
     */
    private void doSingleAttempt(Request request, Parser<? extends Message> responseParser,
                                  Message requestMessage, String grpcPath, int timeout,
                                  DefaultResponseFuture responseFuture, boolean autoRemove) {
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
                // incrErrorCount is handled by whenComplete callback above
            } else {
                incrErrorCount();
            }
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient request failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }

    /**
     * Create the per-stream handler that decodes the gRPC response and
     * completes the {@link DefaultResponseFuture} with a {@link DefaultResponse}
     * wrapping the protobuf message and any trailer metadata.
     */
    private WireStreamResponseHandler newResponseHandler(
            Parser<? extends Message> responseParser, DefaultResponseFuture responseFuture) {
        return new WireStreamResponseHandler(responseParser, responseFuture, maxMessageSize,
                maxInboundMetadataSize,
                message -> {
                    DefaultResponse response = new DefaultResponse(responseFuture.getRequestId());
                    response.setValue(message);
                    return response;
                },
                () -> removeCallback(responseFuture.getRequestId()),
                true);
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
                                    responseParser, publisher, maxMessageSize, maxInboundMetadataSize))
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
     * Open a bidirectional streaming call: send a stream of request items and
     * receive a stream of response items concurrently using the gRPC wire format.
     * <p>
     * HEADERS are sent immediately (without END_STREAM). Each item from the
     * {@code requestStream} is encoded as a gRPC DATA frame. When the request
     * stream completes, END_STREAM is sent. Response items are decoded using
     * the provided {@code responseParser}.
     *
     * @param request        the RPC request (carries metadata/attachments)
     * @param requestStream  a publisher emitting client request {@link Message} items
     * @param responseParser the parser for the expected response message type
     * @return a publisher emitting streamed response messages
     */
    public Flow.Publisher<Object> requestBiStream(Request request, Flow.Publisher<Object> requestStream,
                                                   Parser<? extends Message> responseParser) {
        if (!isAvailable()) {
            throw new JawsServiceException("Wire channel is not available: url=" + url.getUri());
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
                                    responseParser, publisher, maxMessageSize, maxInboundMetadataSize))
                            .open().syncUninterruptibly().getNow();

            // Subscriber cancel() → RST_STREAM(CANCEL)
            publisher.setOnCancel(() -> cancelStream(streamChannel));

            // Send HEADERS without END_STREAM (bidi: request stream follows)
            Http2Headers headers = buildRequestHeaders(request, grpcPath, timeout);
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            log.error("Wire bidi HEADERS write failed", f.cause());
                            publisher.completeExceptionally(
                                    new JawsServiceException("Wire bidi HEADERS write failed", f.cause()));
                            incrErrorCount();
                            cancelStream(streamChannel);
                        }
                    });

            // Subscribe to the user's request stream in a separate thread to
            // avoid blocking the caller and potential deadlocks
            Thread subscribeThread = new Thread(() -> requestStream.subscribe(new Flow.Subscriber<>() {
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
                    if (item instanceof Message msg) {
                        ByteBuf frame = WireFrameCodec.encode(msg, streamChannel.alloc(), compression);
                        streamChannel.writeAndFlush(new DefaultHttp2DataFrame(frame, false))
                                .addListener(f -> {
                                    if (!f.isSuccess()) {
                                        log.error("Wire bidi stream item write failed", f.cause());
                                        subscription.cancel();
                                        cancelStream(streamChannel);
                                        incrErrorCount();
                                    }
                                });
                    } else {
                        log.error("Wire bidi stream item must be a protobuf Message but got: {}",
                                item != null ? item.getClass().getName() : "null");
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
                                        log.error("Wire bidi END_STREAM write failed", f.cause());
                                        incrErrorCount();
                                    }
                                });
                    }
                }
            }), "wire-bidi-stream-writer");
            subscribeThread.setDaemon(true);
            subscribeThread.start();

            return publisher;
        } catch (Exception e) {
            log.error("Wire bidi streaming request failed: url={} path={}", url.getUri(), grpcPath, e);
            publisher.completeExceptionally(e);
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient requestBiStream failed: url="
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

    /**
     * @return the DNS resolver, or null if DNS service discovery is not enabled
     */
    public WireDnsResolver getDnsResolver() {
        return dnsResolver;
    }

    @Override
    protected void doClose() {
        connectivityTracker.shutdown();
        if (dnsResolver != null) {
            dnsResolver.stop();
            dnsResolver = null;
        }
        super.doClose();
    }

    /**
     * @return the connectivity state tracker for this client
     */
    public WireConnectivityTracker getConnectivityTracker() {
        return connectivityTracker;
    }

    /**
     * Delegate to the base class reconnect, called by {@link WireGoAwayHandler}
     * when a GOAWAY frame is received. Package-private to restrict access
     * to the wire module.
     */
    void reconnectOnGoAway() {
        reconnect();
    }
}
