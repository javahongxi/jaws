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
import io.netty.util.AsciiString;
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
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.http2.AbstractHttp2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * gRPC client implementation based on Netty HTTP/2. The Netty bootstrap
 * skeleton, optional TLS with ALPN, multi-connection round-robin, lazy
 * reconnection, and lifecycle state are provided by
 * {@link AbstractHttp2Client}; this class implements only the gRPC wire
 * semantics.
 * <p>
 * Connection-loss policy: call paths must not pre-check {@link #isAvailable()}.
 * Availability is judged per call by {@code activeChannel()}, which reconnects
 * lazily and then fails with a named {@link JawsServiceException} if the peer is
 * still unreachable. A pre-check would short-circuit exactly that recovery: a
 * peer that dies without saying goodbye sends no GOAWAY, so nothing else would
 * ever dial again and a revived peer would stay unusable until this process
 * restarted. A deliberately {@code close()}d client still fails fast, because
 * {@code reconnect()} honours the close state and {@code doClose()} drops the
 * channel array.
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
    /** Encoding name configured for outbound compression, resolved per call. */
    private final String compression;
    /** Which encoding names {@link #compression} and call options may select. */
    private volatile CompressorRegistry compressorRegistry =
            CompressorRegistry.getDefaultInstance();
    /** What this client can decompress, and what it advertises to servers. */
    private volatile DecompressorRegistry decompressorRegistry =
            DecompressorRegistry.getDefaultInstance();
    /** Client keepalive interval; 0 means disabled. */
    private final long keepaliveTimeMs;
    /** Client keepalive ACK timeout. */
    private final long keepaliveTimeoutMs;
    /** Retry policy; null when retries are disabled (maxAttempts ≤ 1). */
    private final WireRetryPolicy retryPolicy;
    /** gRPC connectivity state tracker. */
    private final WireConnectivityTracker connectivityTracker = new WireConnectivityTracker();
    /** Ordered list of client interceptors applied to all outbound calls. */
    private final List<WireClientInterceptor> clientInterceptors = new CopyOnWriteArrayList<>();
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
        // Kept as a name, not resolved here: the registries may still be
        // replaced after construction, and resolveCompressor warns when a name
        // it cannot honour is configured
        this.compression = url.getParameter(UrlParam.Transport.COMPRESSION);
        this.keepaliveTimeMs = url.getLongParameter(UrlParam.Transport.KEEPALIVE_TIME_MS);
        this.keepaliveTimeoutMs = url.getLongParameter(UrlParam.Transport.KEEPALIVE_TIMEOUT_MS);
        this.retryPolicy = WireRetryPolicy.fromUrl(url);
        WireIgnoredParameters.warnIgnored(log, url, "WireClient");
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
     * Open the connection and drive the gRPC connectivity state machine
     * (CONNECTING → READY, or TRANSIENT_FAILURE on error).
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
        return request(request, responseParser, WireCallOptions.DEFAULT);
    }

    /**
     * Send a gRPC request with per-call options (deadline / compressor override).
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @param options        per-call options; {@link WireCallOptions#DEFAULT} inherits configured settings
     * @return a pending response future completed asynchronously by the stream handler
     */
    public Response request(Request request, Parser<? extends Message> responseParser,
                            WireCallOptions options) {
        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message requestMessage)) {
            throw new JawsServiceException(
                    "WireClient request argument must be a protobuf Message; got: "
                            + (args != null && args.length > 0 ? args[0].getClass().getName() : "null"));
        }

        // Build gRPC path: /{interfaceName}/{methodName}
        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int timeout = resolveDeadline(request, options);
        Compressor compressor = resolveCompressor(options);

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout);

        if (retryPolicy != null) {
            // Retry-enabled path: the callback stays in the map across attempts
            // and is only removed on success or final failure
            registerCallback(request.getRequestId(), responseFuture);
            attemptRequest(request, responseParser, requestMessage, grpcPath, timeout, compressor,
                    responseFuture, new AtomicInteger(0));
        } else {
            // Non-retry path: original behaviour
            doSingleAttempt(request, responseParser, requestMessage, grpcPath, timeout, compressor,
                    responseFuture);
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
                                Message requestMessage, String grpcPath, int timeout, Compressor compressor,
                                DefaultResponseFuture responseFuture, AtomicInteger attemptCounter) {
        int attempt = attemptCounter.get();
        io.netty.channel.Channel streamChannel = null;
        try {
            io.netty.channel.Channel connChannel = activeChannel();
            ClientStreamTracer tracer = newStreamTracer(grpcPath);

            WireStreamResponseHandler handler = new WireStreamResponseHandler(
                    responseParser, responseFuture, maxMessageSize, maxInboundMetadataSize,
                    message -> {
                        DefaultResponse response = new DefaultResponse(responseFuture.getRequestId());
                        response.setValue(message);
                        return response;
                    },
                    () -> removeCallback(responseFuture.getRequestId()),
                    false /* retry loop manages the callback */,
                    decompressorRegistry(),
                    tracer);

            final io.netty.channel.Channel streamChannel0 = new Http2StreamChannelBootstrap(connChannel)
                    .handler(handler)
                    .open().syncUninterruptibly().getNow();
            streamChannel = streamChannel0;

            // Retry-coordinated callback: only one callback per completion
            // wins the CAS race to schedule the next attempt
            responseFuture.whenComplete((r, t) -> {
                if (t == null || ExceptionUtils.isBizException(t)) {
                    resetErrorCount();
                    removeCallback(responseFuture.getRequestId());
                    return;
                }
                incrErrorCount();
                cancelStream(streamChannel0);

                // CAS: only the first callback per completion event acts
                if (attemptCounter.compareAndSet(attempt, attempt + 1)
                        && retryPolicy.hasAnotherAttempt(attempt)
                        && WireRetryPolicy.isRetryableFailure(t)) {
                    long delay = retryPolicy.backoffDelayMs(attempt);
                    log.info("gRPC retry: attempt {}/{}, backing off {}ms for path={}",
                            attempt + 1, retryPolicy.maxAttempts(), delay, grpcPath);
                    RETRY_SCHEDULER.schedule(() ->
                            attemptRequest(request, responseParser, requestMessage,
                                    grpcPath, timeout, compressor, responseFuture, attemptCounter),
                            delay, TimeUnit.MILLISECONDS);
                } else if (!retryPolicy.hasAnotherAttempt(attempt)
                        || !WireRetryPolicy.isRetryableFailure(t)) {
                    // Non-retryable or exhausted: clean up
                    removeCallback(responseFuture.getRequestId());
                }
                // else: another callback already claimed the retry
            });

            // Run client interceptor chain, then drive the single request
            WireClientCall call = buildClientChain(new ClientCallImpl(
                    streamChannel0, request, grpcPath, timeout, compressor,
                    mutableCallContext(request), failFuture(responseFuture), tracer, true));
            call.sendMessage(requestMessage);
            call.halfClose();
        } catch (Exception e) {
            if (streamChannel != null) {
                streamChannel.close();
            }
            if (attemptCounter.compareAndSet(attempt, attempt + 1)
                    && retryPolicy.hasAnotherAttempt(attempt)
                    && WireRetryPolicy.isRetryableFailure(e)) {
                long delay = retryPolicy.backoffDelayMs(attempt);
                log.info("gRPC retry: attempt {}/{}, backing off {}ms for path={}",
                        attempt + 1, retryPolicy.maxAttempts(), delay, grpcPath);
                RETRY_SCHEDULER.schedule(() ->
                        attemptRequest(request, responseParser, requestMessage,
                                grpcPath, timeout, compressor, responseFuture, attemptCounter),
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
                                  Message requestMessage, String grpcPath, int timeout, Compressor compressor,
                                  DefaultResponseFuture responseFuture) {
        io.netty.channel.Channel streamChannel = null;
        try {
            io.netty.channel.Channel connChannel = activeChannel();
            ClientStreamTracer tracer = newStreamTracer(grpcPath);

            // Open a new HTTP/2 stream; the handler completes the future
            // when the gRPC response END_STREAM arrives
            final io.netty.channel.Channel streamChannel0 = new Http2StreamChannelBootstrap(connChannel)
                    .handler(newResponseHandler(responseParser, responseFuture, tracer))
                    .open().syncUninterruptibly().getNow();
            streamChannel = streamChannel0;

            // Register callback for timeout + OOM protection; timeout → cancel
            // triggers whenComplete below → RST_STREAM(CANCEL) so the server
            // stops working on it (gRPC cancellation semantics)
            registerCallback(request.getRequestId(), responseFuture);
            responseFuture.whenComplete((r, t) -> {
                if (t == null || ExceptionUtils.isBizException(t)) {
                    resetErrorCount();
                } else {
                    incrErrorCount();
                    cancelStream(streamChannel0);
                }
            });

            // Run client interceptor chain, then drive the single request
            WireClientCall call = buildClientChain(new ClientCallImpl(
                    streamChannel0, request, grpcPath, timeout, compressor,
                    mutableCallContext(request), failFuture(responseFuture), tracer, true));
            call.sendMessage(requestMessage);
            call.halfClose();
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
            if (streamChannel != null) {
                streamChannel.close();
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
            Parser<? extends Message> responseParser, DefaultResponseFuture responseFuture,
            ClientStreamTracer tracer) {
        return new WireStreamResponseHandler(responseParser, responseFuture, maxMessageSize,
                maxInboundMetadataSize,
                message -> {
                    DefaultResponse response = new DefaultResponse(responseFuture.getRequestId());
                    response.setValue(message);
                    return response;
                },
                () -> removeCallback(responseFuture.getRequestId()),
                true,
                decompressorRegistry(),
                tracer);
    }

    /**
     * Send a server-streaming gRPC request and return a {@link StreamSource}
     * that emits each streamed response item. Cancelling via the returned
     * source sends RST_STREAM(CANCEL) to abort the stream on the server
     * (gRPC cancellation semantics).
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @return a source emitting streamed response messages
     */
    public StreamSource<Object> requestStream(Request request, Parser<? extends Message> responseParser) {
        return requestStream(request, responseParser, WireCallOptions.DEFAULT);
    }

    /**
     * Send a server-streaming gRPC request with per-call options.
     *
     * @param request        the RPC request; {@code arguments[0]} must be a protobuf {@link Message}
     * @param responseParser the parser for the expected response message type
     * @param options        per-call options; {@link WireCallOptions#DEFAULT} inherits configured settings
     * @return a source emitting streamed response messages
     */
    public StreamSource<Object> requestStream(Request request, Parser<? extends Message> responseParser,
                                              WireCallOptions options) {
        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof Message requestMessage)) {
            throw new JawsServiceException(
                    "WireClient requestStream argument must be a protobuf Message; got: "
                            + (args != null && args.length > 0 ? args[0].getClass().getName() : "null"));
        }

        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int timeout = resolveDeadline(request, options);
        Compressor compressor = resolveCompressor(options);

        StreamSubject<Object> observer = new StreamSubject<>();
        io.netty.channel.Channel streamChannel = null;
        try {
            io.netty.channel.Channel connChannel = activeChannel();
            ClientStreamTracer tracer = newStreamTracer(grpcPath);

            final io.netty.channel.Channel streamChannel0 =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireStreamStreamingHandler(
                                    responseParser, observer, maxMessageSize, maxInboundMetadataSize,
                                    decompressorRegistry(),
                                    tracer))
                            .open().syncUninterruptibly().getNow();
            streamChannel = streamChannel0;

            // Subscriber cancel() → RST_STREAM(CANCEL): the server observes the
            // reset and stops producing (gRPC cancellation semantics)
            observer.setOnCancel(() -> cancelStream(streamChannel0));

            // Run client interceptor chain, then send the single request and half-close
            Consumer<Throwable> writeFailure = t -> {
                observer.onError(t);
                incrErrorCount();
                cancelStream(streamChannel0);
            };
            WireClientCall call = buildClientChain(new ClientCallImpl(
                    streamChannel0, request, grpcPath, timeout, compressor,
                    mutableCallContext(request), writeFailure, tracer, true));
            call.sendMessage(requestMessage);
            call.halfClose();

            return observer;
        } catch (Exception e) {
            log.error("Wire streaming request failed: url={} path={}", url.getUri(), grpcPath, e);
            observer.onError(e);
            if (streamChannel != null) {
                streamChannel.close();
            }
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient requestStream failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }

    /**
     * Open a client-streaming call: send a stream of request items and receive
     * a single response using the gRPC wire format.
     * <p>
     * HEADERS are sent immediately (without END_STREAM). Each item from the
     * {@code requestStream} is encoded as a gRPC DATA frame. When the request
     * stream completes, END_STREAM is sent and the server processes the
     * accumulated items into a single response, which is delivered through
     * the returned {@link StreamSource}.
     *
     * @param request        the RPC request (carries metadata/attachments)
     * @param requestStream  a source of client request {@link Message} items
     * @param responseParser the parser for the expected response message type
     * @return a source emitting the single response message
     */
    public StreamSource<Object> requestStream(Request request, StreamSource<Object> requestStream,
                                                       Parser<? extends Message> responseParser) {
        return requestStream(request, requestStream, responseParser, WireCallOptions.DEFAULT);
    }

    /**
     * Open a client-streaming call with per-call options.
     *
     * @param request        the RPC request (carries metadata/attachments)
     * @param requestStream  a source of client request {@link Message} items
     * @param responseParser the parser for the expected response message type
     * @param options        per-call options; {@link WireCallOptions#DEFAULT} inherits configured settings
     * @return a source emitting the single response message
     */
    public StreamSource<Object> requestStream(Request request, StreamSource<Object> requestStream,
                                                       Parser<? extends Message> responseParser,
                                                       WireCallOptions options) {
        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int timeout = resolveDeadline(request, options);
        Compressor compressor = resolveCompressor(options);

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout);
        // Use StreamSubject (synchronous delivery) to guarantee onNext fires
        // before onComplete on late subscribers.
        StreamSubject<Object> observer = new StreamSubject<>();
        io.netty.channel.Channel streamChannel = null;
        try {
            io.netty.channel.Channel connChannel = activeChannel();
            ClientStreamTracer tracer = newStreamTracer(grpcPath);

            final io.netty.channel.Channel streamChannel0 =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(newResponseHandler(responseParser, responseFuture, tracer))
                            .open().syncUninterruptibly().getNow();
            streamChannel = streamChannel0;

            // Register before writing so a fast failure can find and fail the future
            registerCallback(request.getRequestId(), responseFuture);

            // Bridge responseFuture → StreamSubject
            responseFuture.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    observer.onError(throwable);
                } else if (response.getThrowable() != null) {
                    observer.onError(response.getThrowable());
                } else {
                    if (response.getValue() != null) {
                        observer.onNext(response.getValue());
                    }
                    observer.onCompleted();
                }
                if (throwable == null || ExceptionUtils.isBizException(throwable)) {
                    resetErrorCount();
                } else {
                    incrErrorCount();
                }
            });

            // Run the client interceptor chain once; the caller's subscription
            // then drives the wrapped call, so interceptors observe every item
            // and the trailing half-close. HEADERS go out lazily with the first
            // item (or the half-close when the stream carries none).
            Consumer<Throwable> writeFailure = t -> {
                DefaultResponse errorResponse = new DefaultResponse(request.getRequestId());
                errorResponse.setThrowable(t);
                responseFuture.onFailure(errorResponse);
                cancelStream(streamChannel0);
            };
            final WireClientCall call = buildClientChain(new ClientCallImpl(
                    streamChannel0, request, grpcPath, timeout, compressor,
                    mutableCallContext(request), writeFailure, tracer, false));

            // Subscribe to the caller's request stream and forward each item
            // to the network as it is produced.
            requestStream.subscribe(new StreamObserver<>() {
                @Override
                public void onNext(Object item) {
                    if (item instanceof Message msg) {
                        call.sendMessage(msg);
                    } else {
                        log.error("Wire client-stream item must be a protobuf Message but got: {}",
                                item != null ? item.getClass().getName() : "null");
                        call.cancel("invalid client-stream request item type");
                        incrErrorCount();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.error("Client stream request error", throwable);
                    call.cancel("request stream error");
                    incrErrorCount();
                }

                @Override
                public void onCompleted() {
                    call.halfClose();
                }
            });

            return observer;
        } catch (Exception e) {
            log.error("Wire client-stream request failed: url={} path={}", url.getUri(), grpcPath, e);
            observer.onError(e);
            if (streamChannel != null) {
                streamChannel.close();
            }
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient client-stream request failed: url="
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
     * @param requestStream  a source of client request {@link Message} items
     * @param responseParser the parser for the expected response message type
     * @return a source emitting streamed response messages
     */
    public StreamSource<Object> requestBidiStream(Request request, StreamSource<Object> requestStream,
                                                  Parser<? extends Message> responseParser) {
        return requestBidiStream(request, requestStream, responseParser, WireCallOptions.DEFAULT);
    }

    /**
     * Open a bidirectional streaming call with per-call options.
     *
     * @param request        the RPC request (carries metadata/attachments)
     * @param requestStream  a source of client request {@link Message} items
     * @param responseParser the parser for the expected response message type
     * @param options        per-call options; {@link WireCallOptions#DEFAULT} inherits configured settings
     * @return a source emitting streamed response messages
     */
    public StreamSource<Object> requestBidiStream(Request request, StreamSource<Object> requestStream,
                                                  Parser<? extends Message> responseParser,
                                                  WireCallOptions options) {
        String grpcPath = "/" + request.getInterfaceName() + "/" + request.getMethodName();

        int timeout = resolveDeadline(request, options);
        Compressor compressor = resolveCompressor(options);

        StreamSubject<Object> observer = new StreamSubject<>();
        io.netty.channel.Channel streamChannel = null;
        try {
            io.netty.channel.Channel connChannel = activeChannel();
            ClientStreamTracer tracer = newStreamTracer(grpcPath);

            final io.netty.channel.Channel streamChannel0 =
                    new Http2StreamChannelBootstrap(connChannel)
                            .handler(new WireStreamStreamingHandler(
                                    responseParser, observer, maxMessageSize, maxInboundMetadataSize,
                                    decompressorRegistry(),
                                    tracer))
                            .open().syncUninterruptibly().getNow();
            streamChannel = streamChannel0;

            // Subscriber cancel() → RST_STREAM(CANCEL)
            observer.setOnCancel(() -> cancelStream(streamChannel0));

            // Run the client interceptor chain once; the caller's subscription
            // drives the wrapped call, so interceptors observe every outbound
            // item and the trailing half-close. HEADERS go out lazily with the
            // first item (or the half-close when the request stream is empty).
            Consumer<Throwable> writeFailure = t -> {
                observer.onError(t);
                incrErrorCount();
                cancelStream(streamChannel0);
            };
            final WireClientCall call = buildClientChain(new ClientCallImpl(
                    streamChannel0, request, grpcPath, timeout, compressor,
                    mutableCallContext(request), writeFailure, tracer, false));

            // Subscribe to the caller's request stream and forward each item
            // to the network as it is produced.
            requestStream.subscribe(new StreamObserver<>() {
                @Override
                public void onNext(Object item) {
                    if (item instanceof Message msg) {
                        call.sendMessage(msg);
                    } else {
                        log.error("Wire bidi stream item must be a protobuf Message but got: {}",
                                item != null ? item.getClass().getName() : "null");
                        call.cancel("invalid bidi request item type");
                        incrErrorCount();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.error("Client bidi request stream error", throwable);
                    call.cancel("request stream error");
                    incrErrorCount();
                }

                @Override
                public void onCompleted() {
                    call.halfClose();
                }
            });

            return observer;
        } catch (Exception e) {
            log.error("Wire bidi streaming request failed: url={} path={}", url.getUri(), grpcPath, e);
            observer.onError(e);
            if (streamChannel != null) {
                streamChannel.close();
            }
            incrErrorCount();
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("WireClient requestBiStream failed: url="
                    + url.getUri() + " path=" + grpcPath, e);
        }
    }

    private static final AsciiString METHOD_POST = AsciiString.of("POST");
    private static final AsciiString SCHEME_HTTP = AsciiString.of("http");
    private static final AsciiString SCHEME_HTTPS = AsciiString.of("https");

    /**
     * Build the gRPC request HEADERS: pseudo-headers, content-type, the
     * mandatory {@code te: trailers}, user-agent, encoding advertisement, the
     * caller's deadline, and the request attachments as custom metadata.
     */
    private Http2Headers buildRequestHeaders(Request request, String grpcPath, int timeout,
                                             Compressor compressor) {
        Http2Headers headers = new DefaultHttp2Headers()
                .method(METHOD_POST)
                .scheme(getSslContext() != null ? SCHEME_HTTPS : SCHEME_HTTP)
                .path(AsciiString.of(grpcPath))
                .authority(AsciiString.of(url.getHostPort()))
                .set(WireConstants.HEADER_CONTENT_TYPE, WireConstants.CONTENT_TYPE_GRPC)
                .set(WireConstants.HEADER_TE, WireConstants.TE_TRAILERS)
                .set(WireConstants.HEADER_USER_AGENT, WireConstants.USER_AGENT)
                // Propagate the caller's deadline so the server can honor it
                // and report DEADLINE_EXCEEDED (gRPC timeout semantics)
                .set(WireStatus.GRPC_TIMEOUT, WireStatus.encodeTimeout(timeout));
        // Advertise what this client can decompress, taken from the registry:
        // identity is registered but not advertised, so the default offer is
        // exactly "gzip" — an uncompressed frame needs no agreement
        String advertised = decompressorRegistry.rawAdvertisedEncodings();
        if (!advertised.isEmpty()) {
            headers.set(WireConstants.GRPC_ACCEPT_ENCODING, advertised);
        }
        // A client names its encoding only when it is compressing; absence
        // already means identity, which is why only the server writes the
        // header unconditionally
        if (compressor != null && compressor != Codec.Identity.NONE) {
            headers.set(WireConstants.GRPC_ENCODING, compressor.getMessageEncoding());
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
     * Resolve the deadline for a call: an explicit {@link WireCallOptions#deadlineMs()}
     * override wins; otherwise fall back to the method / service / global / URL
     * timeout resolution chain.
     *
     * @param request the RPC request (for method/service keys)
     * @param options per-call options (may be {@code null} or {@link WireCallOptions#DEFAULT})
     * @return the effective deadline in milliseconds
     */
    int resolveDeadline(Request request, WireCallOptions options) {
        if (options != null && options.deadlineMs() != null) {
            return options.deadlineMs();
        }
        int urlTimeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                UrlParam.Transport.REQUEST_TIMEOUT.intValue());
        return resolveTimeout(request, urlTimeout);
    }

    /**
     * Resolve the request compressor for a call: an explicit
     * {@link WireCallOptions#compressor()} override wins (validated against the
     * supported set, falling back to the client default when unsupported);
     * otherwise use the client's configured {@code compression}.
     *
     * @param options per-call options (may be {@code null} or {@link WireCallOptions#DEFAULT})
     * @return the effective compressor ("identity" or a supported encoding)
     */
    Compressor resolveCompressor(WireCallOptions options) {
        if (options == null || options.compressor() == null) {
            return lookupCompressor(compression);
        }
        String c = options.compressor();
        if (WireConstants.ENCODING_IDENTITY.equals(c)) {
            return Codec.Identity.NONE;
        }
        Compressor compressor = compressorRegistry.lookupCompressor(c);
        if (compressor != null) {
            return compressor;
        }
        log.warn("Unsupported wire call compressor '{}', falling back to client default '{}'",
                c, compression);
        return lookupCompressor(compression);
    }

    /**
     * Resolve a configured or requested encoding name to a compressor.
     *
     * @param name the encoding name, possibly {@code null}
     * @return the registered compressor, or identity for a name nothing is
     *         registered under — warned rather than swallowed, because a codec
     *         that silently does nothing is worse than one refused outright
     */
    private Compressor lookupCompressor(String name) {
        if (name == null || name.isEmpty() || WireConstants.ENCODING_IDENTITY.equals(name)) {
            return Codec.Identity.NONE;
        }
        Compressor compressor = compressorRegistry.lookupCompressor(name);
        if (compressor == null) {
            log.warn("No compressor registered for '{}', sending uncompressed", name);
            return Codec.Identity.NONE;
        }
        return compressor;
    }

    /**
     * Replace the compressors selectable by the {@code compression} parameter
     * and by {@link WireCallOptions#withCompressor(String)}. Mirrors
     * grpc-java's {@code ManagedChannelBuilder.compressorRegistry(...)}.
     *
     * @param compressorRegistry the registry to use, {@code null} for the default
     */
    public void setCompressorRegistry(CompressorRegistry compressorRegistry) {
        this.compressorRegistry = compressorRegistry != null
                ? compressorRegistry : CompressorRegistry.getDefaultInstance();
    }

    /**
     * Replace what this client can decompress and advertises in
     * {@code grpc-accept-encoding}. Mirrors grpc-java's
     * {@code ManagedChannelBuilder.decompressorRegistry(...)}.
     *
     * @param decompressorRegistry the registry to use, {@code null} for the default
     */
    public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
        this.decompressorRegistry = decompressorRegistry != null
                ? decompressorRegistry : DecompressorRegistry.getDefaultInstance();
    }

    /**
     * @return the decompressions this client can perform, needed by its
     *         per-stream response handlers
     */
    DecompressorRegistry decompressorRegistry() {
        return decompressorRegistry;
    }

    /**
     * Add a client interceptor applied to all outbound calls.
     * Interceptors execute in registration order (first added = outermost).
     *
     * @param interceptor the interceptor to add
     */
    public void addInterceptor(WireClientInterceptor interceptor) {
        clientInterceptors.add(interceptor);
    }

    /**
     * @return the registered client interceptors (unmodifiable view)
     */
    public List<WireClientInterceptor> getClientInterceptors() {
        return List.copyOf(clientInterceptors);
    }

    /**
     * Creates the per-stream observer of outbound calls: message counts, byte
     * sizes and terminal statuses. Configured programmatically rather than by
     * URL because a tracer is code, not a deployment knob.
     */
    private volatile ClientStreamTracer.Factory streamTracerFactory;

    /**
     * Observe every outbound stream. {@code null} leaves each stream on
     * {@link ClientStreamTracer#NOOP}, which costs nothing. A tracer is created
     * per <em>attempt</em>, so a retried call reports one stream per try.
     *
     * @param streamTracerFactory the factory, or {@code null} to stop observing
     */
    public void setStreamTracerFactory(ClientStreamTracer.Factory streamTracerFactory) {
        this.streamTracerFactory = streamTracerFactory;
    }

    /**
     * The tracer for one outbound stream, shared by that stream's
     * {@link ClientCallImpl} and its response handler so that both directions
     * land on one observer.
     */
    private ClientStreamTracer newStreamTracer(String grpcPath) {
        ClientStreamTracer.Factory factory = streamTracerFactory;
        return factory != null ? factory.newClientStreamTracer(grpcPath) : ClientStreamTracer.NOOP;
    }

    /**
     * Context seeded from the request's attachments, always mutable so an
     * interceptor's {@code putAttachment} succeeds even when the request has no
     * initial attachments ({@link WireCallContext#of} returns the immutable
     * EMPTY singleton for empty maps).
     */
    private static WireCallContext mutableCallContext(Request request) {
        return WireCallContext.mutableCopy(WireCallContext.of(request.getAttachments()));
    }

    /**
     * Wrap {@code realCall} in the registered client interceptors (first added
     * = outermost, mirroring grpc-java's {@code Channel.intercept}). Returns
     * {@code realCall} itself when no interceptor is registered. The caller then
     * drives the returned call's {@code sendMessage}/{@code halfClose}, so an
     * interceptor observes not just the opening metadata but every outbound
     * message and the half-close.
     */
    private WireClientCall buildClientChain(WireClientCall realCall) {
        if (clientInterceptors.isEmpty()) {
            return realCall;
        }
        WireClientCallHandler chain = call -> call;
        for (int i = clientInterceptors.size() - 1; i >= 0; i--) {
            WireClientInterceptor interceptor = clientInterceptors.get(i);
            WireClientCallHandler next = chain;
            chain = outerCall -> interceptor.interceptCall(outerCall, next);
        }
        return chain.newCall(realCall);
    }

    /**
     * Failure sink for future-backed calls (unary, client-streaming): complete
     * the response future exceptionally on a local write error. The future's
     * {@code whenComplete} bridge then handles error counting and (for unary)
     * the RST_STREAM cancellation, so the sink itself does only the completion.
     */
    private Consumer<Throwable> failFuture(DefaultResponseFuture responseFuture) {
        return t -> {
            DefaultResponse errorResponse = new DefaultResponse(responseFuture.getRequestId());
            errorResponse.setThrowable(t);
            responseFuture.onFailure(errorResponse);
        };
    }

    /**
     * {@link WireClientCall} implementation that drives one gRPC call over an
     * already-open HTTP/2 stream. It is the single send-path shared by unary,
     * server-, client- and bidi-streaming, so interceptor wrapping behaves the
     * same for all four shapes.
     * <p>
     * HEADERS are written lazily with the first {@code sendMessage} (or with
     * {@code halfClose} when a client/bidi stream carries no request item),
     * folding in any attachments an interceptor injected. Each {@code sendMessage}
     * emits one DATA frame without END_STREAM; {@code halfClose} emits the
     * terminating empty DATA(END_STREAM). A failed outbound write is handed to
     * {@code failureSink}, which the caller tailors to the response shape (fail
     * the future for unary/client-streaming, signal the observer for
     * server/bidi). A stream the peer has already torn down surfaces via the
     * response handler's {@code channelInactive}, so this sink is a backstop for
     * a local write error, not the normal termination path.
     */
    final class ClientCallImpl implements WireClientCall {
        private final io.netty.channel.Channel streamChannel;
        private final Request request;
        private final String grpcPath;
        private final int timeout;
        /** Compressor negotiated for this call's request messages. */
        private final Compressor compressor;
        private final WireCallContext callContext;
        private final Consumer<Throwable> failureSink;
        /** Observer shared with this stream's response handler; never {@code null}. */
        private final ClientStreamTracer tracer;
        private boolean headersSent;
        /** Whether {@link #halfClose} has emitted END_STREAM (directly or folded). */
        private boolean halfClosed;
        /**
         * True for single-request shapes (unary, server-streaming) whose call
         * sites invoke {@code sendMessage + halfClose} back-to-back on one
         * thread: the message frame is staged and written with END_STREAM
         * folded in at half-close — one DATA frame instead of two, exactly
         * the shape grpc-java sends. False for interactive shapes
         * (client-streaming, bidi), where every message must reach the peer
         * immediately or a half-closing peer would deadlock the exchange.
         */
        private final boolean foldEndStream;
        /** Staged encoded frame; non-null only while {@link #foldEndStream} is set. */
        private ByteBuf pendingFrame;
        /**
         * Outbound message counter. Not atomic: callers of {@link #sendMessage}
         * are serialized by the contract of the API driving this call, exactly
         * as in grpc-java's {@code ClientCallImpl}.
         */
        private int outboundMessageNumber;

        ClientCallImpl(io.netty.channel.Channel streamChannel, Request request,
                        String grpcPath, int timeout, Compressor compressor,
                        WireCallContext callContext, Consumer<Throwable> failureSink,
                        ClientStreamTracer tracer, boolean foldEndStream) {
            this.streamChannel = streamChannel;
            this.request = request;
            this.grpcPath = grpcPath;
            this.timeout = timeout;
            this.compressor = compressor;
            this.callContext = callContext;
            this.failureSink = failureSink;
            this.tracer = tracer;
            this.foldEndStream = foldEndStream;
        }

        @Override
        public WireCallContext context() {
            return callContext;
        }

        @Override
        public String path() {
            return grpcPath;
        }

        @Override
        public void putAttachment(String key, String value) {
            callContext.putAttachment(key, value);
        }

        @Override
        public void sendMessage(Message message) {
            if (!streamChannel.isActive()) {
                return;
            }
            // Folding shapes: the previous staged message can no longer carry
            // END_STREAM, so flush it unflagged before staging this one
            flushPending();
            writeHeadersIfNeeded();
            ByteBuf content = WireFrameCodec.encode(message, streamChannel.alloc(), compressor);
            // Reported before the buffer is handed to the pipeline, which
            // consumes and releases it asynchronously
            tracer.outboundMessageSent(outboundMessageNumber++,
                    WireFrameCodec.payloadSize(content), message.getSerializedSize());
            if (foldEndStream) {
                pendingFrame = content;
            } else {
                streamChannel.writeAndFlush(new DefaultHttp2DataFrame(content, false))
                        .addListener(f -> {
                            if (!f.isSuccess()) {
                                reportWriteFailure("DATA", f.cause());
                            }
                        });
            }
        }

        @Override
        public void halfClose() {
            if (halfClosed) {
                return;
            }
            halfClosed = true;
            if (!streamChannel.isActive()) {
                releasePending();
                return;
            }
            writeHeadersIfNeeded();
            if (pendingFrame != null) {
                // Fold END_STREAM into the staged message frame: unary and
                // server-streaming go out as HEADERS + one DATA(END_STREAM),
                // same as grpc-java, instead of an extra empty DATA frame
                ByteBuf content = pendingFrame;
                pendingFrame = null;
                streamChannel.writeAndFlush(new DefaultHttp2DataFrame(content, true))
                        .addListener(f -> {
                            if (!f.isSuccess()) {
                                reportWriteFailure("DATA", f.cause());
                            }
                        });
            } else {
                streamChannel.writeAndFlush(new DefaultHttp2DataFrame(true))
                        .addListener(f -> {
                            if (!f.isSuccess()) {
                                reportWriteFailure("END_STREAM", f.cause());
                            }
                        });
            }
        }

        /** Writes the staged frame unflagged, making room for the next message. */
        private void flushPending() {
            if (pendingFrame == null) {
                return;
            }
            ByteBuf content = pendingFrame;
            pendingFrame = null;
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(content, false))
                    .addListener(f -> {
                        if (!f.isSuccess()) {
                            reportWriteFailure("DATA", f.cause());
                        }
                    });
        }

        private void releasePending() {
            if (pendingFrame != null) {
                pendingFrame.release();
                pendingFrame = null;
            }
        }

        @Override
        public void cancel(String reason) {
            releasePending();
            cancelStream(streamChannel);
        }

        private void writeHeadersIfNeeded() {
            if (headersSent) {
                return;
            }
            headersSent = true;
            // Sync interceptor-modified context into request attachments so
            // buildRequestHeaders propagates them as gRPC metadata. setAttachment
            // lazily creates the request's map, which may start as an immutable
            // empty map when the caller set no attachments.
            for (Map.Entry<String, String> entry : callContext.getAttachments().entrySet()) {
                request.setAttachment(entry.getKey(), entry.getValue());
            }
            Http2Headers headers = buildRequestHeaders(request, grpcPath, timeout, compressor);
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            tracer.outboundHeaders();
        }

        private void reportWriteFailure(String frame, Throwable cause) {
            failureSink.accept(new JawsServiceException(
                    "Wire " + frame + " write failed: requestId=" + request.getRequestId()
                            + ", cause=" + cause, cause));
        }
    }

    /**
     * @return the connectivity state tracker for this client
     */
    public WireConnectivityTracker getConnectivityTracker() {
        return connectivityTracker;
    }

    @Override
    protected void doClose() {
        connectivityTracker.shutdown();
        super.doClose();
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
