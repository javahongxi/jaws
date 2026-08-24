package org.hongxi.jaws.transport.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.DefaultResponseFuture;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.serialization.Serialization;
import org.hongxi.jaws.transport.ChannelState;
import org.hongxi.jaws.transport.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * HTTP/2-based {@link Client} implementation maintaining a single multiplexed
 * h2c connection per remote URL. Each request opens its own HTTP/2 stream via
 * {@link Http2StreamChannelBootstrap}, so concurrent requests never contend on
 * application-level framing — the head-of-line blocking present in the
 * request-id multiplexed jaws TCP protocol is eliminated by design.
 * <p>
 * Like {@code NettyClient}, async requests register a {@link ResponseFuture}
 * callback guarded by a one-shot timeout on a shared HashedWheelTimer; the
 * per-stream {@link Http2StreamResponseHandler} completes the future when the
 * response END_STREAM arrives, or fails it on stream reset/close.
 * <p>
 * Idle-connection liveness relies on HTTP/2 PING frames managed by Netty's
 * {@code Http2FrameCodec} (keepalive) instead of application-level heartbeats.
 *
 * @author shenhongxi
 */
public class Http2Client implements Client {
    private static final Logger log = LoggerFactory.getLogger(Http2Client.class);

    private static final int HTTP2_CLIENT_MAX_REQUEST = 20000;

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();

    /**
     * Per-request timeout scheduler, same pattern as NettyClient.
     */
    private static final HashedWheelTimer timeoutTimer = new HashedWheelTimer(
            new io.netty.util.concurrent.DefaultThreadFactory("jaws-http2-client-timeout", true),
            30, TimeUnit.MILLISECONDS);

    private final URL url;
    private final Serialization serialization;

    private Bootstrap bootstrap;
    // volatile: written under the instance lock in open()/close(), read lock-free in request()
    private volatile io.netty.channel.Channel channel;
    private volatile ChannelState state = ChannelState.UNINIT;

    /**
     * Async requests need to register a callback future.
     * Removal triggers: 1) response received  2) timeout task cancels it  3) close().
     */
    private final ConcurrentMap<Long, ResponseFuture> callbackMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Timeout> timeoutMap = new ConcurrentHashMap<>();

    public Http2Client(URL url) {
        this.url = url;
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

        int timeout = url.getMethodParameter(
                request.getMethodName(), request.getParamDesc(),
                UrlParam.Transport.REQUEST_TIMEOUT.getName(), UrlParam.Transport.REQUEST_TIMEOUT.intValue());

        DefaultResponseFuture responseFuture = new DefaultResponseFuture(request, timeout, url);

        try {
            if (!channel.isActive()) {
                reconnect();
            }
            if (!channel.isActive()) {
                throw new JawsServiceException("HTTP/2 channel is not active: url="
                        + url.getUri() + RpcUtils.toString(request));
            }

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(channel)
                            .handler(new Http2StreamResponseHandler(this, request.getRequestId()))
                            .open().syncUninterruptibly().getNow();

            // Register before writing so a fast failure (channelInactive) can
            // always find and fail the future
            registerCallback(request.getRequestId(), responseFuture);

            byte[] payload = Http2PayloadCodec.encodeRequest(request, serialization);
            Http2Headers headers = new DefaultHttp2Headers()
                    .method("POST")
                    .scheme("http")
                    .path(Http2Constants.PATH)
                    .authority(url.getHostPort())
                    .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.CONTENT_TYPE)
                    .set(Http2Constants.HEADER_SERIALIZATION,
                            url.getParameter(UrlParam.Transport.SERIALIZATION));
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
            if (!channel.isActive()) {
                reconnect();
            }
            if (!channel.isActive()) {
                throw new JawsServiceException("HTTP/2 channel is not active: url="
                        + url.getUri() + RpcUtils.toString(request));
            }

            // Create the streaming handler which doubles as a Flow.Publisher
            Http2StreamClientHandler streamHandler = new Http2StreamClientHandler(serialization);

            io.netty.channel.Channel streamChannel =
                    new Http2StreamChannelBootstrap(channel)
                            .handler(streamHandler)
                            .open().syncUninterruptibly().getNow();

            // Send request headers with streaming mode
            byte[] payload = Http2PayloadCodec.encodeRequest(request, serialization);
            Http2Headers headers = new DefaultHttp2Headers()
                    .method("POST")
                    .scheme("http")
                    .path(Http2Constants.PATH)
                    .authority(url.getHostPort())
                    .set(Http2Constants.HEADER_CONTENT_TYPE, Http2Constants.CONTENT_TYPE)
                    .set(Http2Constants.HEADER_SERIALIZATION,
                            url.getParameter(UrlParam.Transport.SERIALIZATION))
                    .set(Http2Constants.HEADER_STREAMING, StreamType.SERVER.getWireValue());
            streamChannel.write(new DefaultHttp2HeadersFrame(headers));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.wrappedBuffer(payload), true)).addListener(f -> {
                if (!f.isSuccess()) {
                    log.error("HTTP/2 stream write failed for streaming request", f.cause());
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



    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = url.getIntParameter(UrlParam.Transport.CONNECT_TIMEOUT);
        if (timeout <= 0) {
            throw new JawsFrameworkException("Http2Client init failed: connect timeout must be positive but was " + timeout);
        }

        bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.group(nioEventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // HTTP/2 framing & flow control; liveness relies on TCP keepalive
                        // and HTTP/2 PINGs instead of application-level heartbeats
                        pipeline.addLast("http2_codec", Http2FrameCodecBuilder.forClient().build());
                        pipeline.addLast("http2_multiplex", new Http2MultiplexHandler(
                                new io.netty.channel.ChannelInboundHandlerAdapter()));
                    }
                });

        doConnect();
        state = ChannelState.ALIVE;
        log.info("Http2Client opened successfully: url={}", url);
        return true;
    }

    private void doConnect() {
        ChannelFuture future = bootstrap.connect(url.getHost(), url.getPort()).syncUninterruptibly();
        if (!future.isSuccess()) {
            throw new JawsServiceException("Http2Client connect failed: url=" + url.getUri(), future.cause());
        }
        channel = future.channel();
    }

    /**
     * Re-establish the connection. Called lazily from {@link #request(Request)}
     * when the multiplexed connection has gone away.
     */
    synchronized void reconnect() {
        if (state.isCloseState()) {
            return;
        }
        if (channel != null && channel.isActive()) {
            return;
        }
        if (channel != null) {
            channel.close();
            channel = null;
        }
        try {
            doConnect();
            log.info("Http2Client reconnected: url={}", url.getUri());
        } catch (Exception e) {
            log.error("Http2Client reconnect failed: url={}", url.getUri(), e);
        }
    }

    @Override
    public synchronized void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        if (state.isCloseState()) {
            return;
        }

        try {
            // Graceful drain: give in-flight requests a chance to complete
            if (timeout > 0) {
                awaitPendingRequests(timeout);
            }
            cleanup();
            state = ChannelState.CLOSE;
            log.info("Http2Client closed successfully: url={}", url.getUri());
        } catch (Exception e) {
            log.error("Http2Client failed to close: url={}", url.getUri(), e);
        }
    }

    private void cleanup() {
        // Cancel all pending timeout tasks
        timeoutMap.values().forEach(Timeout::cancel);
        timeoutMap.clear();
        // Fail pending futures so callers get an immediate error instead of
        // waiting for request timeout after the connection is torn down
        for (ResponseFuture future : callbackMap.values()) {
            try {
                future.cancel();
            } catch (Exception e) {
                log.error("failed to cancel pending request: uri={} requestId={}",
                        url.getUri(), future.getRequestId(), e);
            }
        }
        callbackMap.clear();
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    /**
     * Wait for in-flight requests to complete, up to the given timeout.
     */
    private void awaitPendingRequests(long timeout) {
        long deadline = System.currentTimeMillis() + timeout;
        while (!callbackMap.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!callbackMap.isEmpty()) {
            log.warn("Http2Client closed while {} pending requests not completed: url={}",
                    callbackMap.size(), url.getUri());
        }
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState() && channel != null && channel.isOpen();
    }

    @Override
    public URL getUrl() {
        return url;
    }

    /**
     * Register a callback for an async request and schedule a per-request timeout.
     * Rejects the request if the concurrent count exceeds the limit to prevent OOM.
     */
    private void registerCallback(long requestId, ResponseFuture responseFuture) {
        if (callbackMap.size() >= HTTP2_CLIENT_MAX_REQUEST) {
            throw new JawsServiceException("Http2Client exceeded max concurrent requests, request rejected, url: "
                    + url.getUri() + " requestId=" + requestId, JawsErrorCode.SERVICE_REJECT);
        }

        callbackMap.put(requestId, responseFuture);

        int timeout = responseFuture.getTimeout();
        if (timeout > 0) {
            Timeout timerTimeout = timeoutTimer.newTimeout(t -> {
                ResponseFuture future = callbackMap.remove(requestId);
                if (future != null) {
                    timeoutMap.remove(requestId);
                    try {
                        future.cancel();
                    } catch (Exception e) {
                        log.error("failed to cancel timeout task: uri={} requestId={}", url.getUri(), requestId, e);
                    }
                }
            }, timeout, TimeUnit.MILLISECONDS);
            timeoutMap.put(requestId, timerTimeout);
        }
    }

    ResponseFuture removeCallback(long requestId) {
        Timeout timeout = timeoutMap.remove(requestId);
        if (timeout != null) {
            timeout.cancel();
        }
        return callbackMap.remove(requestId);
    }
}
