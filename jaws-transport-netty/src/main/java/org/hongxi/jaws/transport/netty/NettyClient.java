package org.hongxi.jaws.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.hongxi.jaws.common.ChannelState;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by shenhongxi on 2020/7/28.
 */
public class NettyClient extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(NettyClient.class);

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();

    private Bootstrap bootstrap;
    private NettyChannel channel;

    private final int fusingThreshold;
    /**
     * consecutive error count
     */
    private final AtomicLong errorCount = new AtomicLong(0);

    /**
     * Per-request timeout scheduler using HashedWheelTimer.
     * Each callback registers a one-shot timeout task at registration time.
     */
    private static final HashedWheelTimer timeoutTimer = new HashedWheelTimer(
            new io.netty.util.concurrent.DefaultThreadFactory("jaws-client-timeout", true),
            30, TimeUnit.MILLISECONDS);

    /**
     * Async requests need to register a callback future.
     * Removal triggers: 1) response received from server  2) timeout task cancels it.
     */
    protected ConcurrentMap<Long, ResponseFuture> callbackMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Timeout> timeoutMap = new ConcurrentHashMap<>();

    public NettyClient(URL url) {
        super(url);
        fusingThreshold = url.getIntParameter(URLParamType.fusingThreshold);
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    @Override
    public Response request(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException("NettyChannel is unavailable: url="
                    + url.getUri() + JawsFrameworkUtils.toString(request));
        }

        boolean async = false;
        Object asyncFlag = RpcContext.getContext().getAttribute(JawsConstants.ASYNC_FLAG);
        if (asyncFlag instanceof Boolean b) {
            async = b;
        }

        Response response;
        try {
            if (!channel.isAvailable()) {
                channel.reconnect();
            }
            if (!channel.isAvailable()) {
                throw new JawsServiceException("NettyChannel is not available: url="
                        + url.getUri() + JawsFrameworkUtils.toString(request));
            }
            response = channel.request(request);
        } catch (Exception e) {
            log.error("request Error: url={} {}, {}", url.getUri(),
                    JawsFrameworkUtils.toString(request), e.getMessage());

            if (e instanceof JawsAbstractException jae) {
                throw jae;
            } else {
                throw new JawsServiceException("NettyClient request Error: url=" +
                        url.getUri() + " " + JawsFrameworkUtils.toString(request), e);
            }
        }

        return async ? response : new DefaultResponse(response);
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = getUrl().getIntParameter(URLParamType.connectTimeout);
        if (timeout <= 0) {
            throw new JawsFrameworkException("NettyClient init Error: timeout(" +
                    timeout + ") <= 0 is forbid.",
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }

        final int maxContentLength = url.getIntParameter(URLParamType.maxContentLength);

        bootstrap = new Bootstrap();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.group(nioEventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("decoder", new NettyDecoder(codec, NettyClient.this, maxContentLength));
                        pipeline.addLast("encoder", new NettyEncoder(maxContentLength));
                        pipeline.addLast("handler", new NettyChannelHandler(NettyClient.this, (Channel channel, Object message) -> {
                            Response response = (Response) message;
                            ResponseFuture responseFuture = NettyClient.this.removeCallback(response.getRequestId());

                            if (responseFuture == null) {
                                log.warn("has response from server, but responseFuture not exist, requestId={}",
                                        response.getRequestId());
                                return CompletableFuture.completedFuture(null);
                            }
                            if (response.getException() != null) {
                                responseFuture.onFailure(response);
                            } else {
                                responseFuture.onSuccess(response);
                            }
                            return CompletableFuture.completedFuture(null);
                        }));
                    }
                });

        // Create single connection
        channel = new NettyChannel(this);
        channel.open();

        log.info("NettyClient finished open: url={}", url);

        // Set available state
        state = ChannelState.ALIVE;
        return true;
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
            cleanup();
            if (state.isUnInitState()) {
                log.info("NettyClient close failed: state={}, url={}", state.value(), url.getUri());
                return;
            }

            // Set close state
            state = ChannelState.CLOSE;
            log.info("NettyClient close Success: url={}", url.getUri());
        } catch (Exception e) {
            log.error("NettyClient close Error: url={}", url.getUri(), e);
        }
    }

    private void cleanup() {
        // Cancel all pending timeout tasks
        timeoutMap.values().forEach(Timeout::cancel);
        timeoutMap.clear();
        // Clear callbacks
        callbackMap.clear();
        // Close the channel
        if (channel != null) {
            channel.close();
        }
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }

    @Override
    public URL getUrl() {
        return url;
    }

    /**
     * Increment the consecutive error count.
     * If the count reaches the fusing threshold, mark this client as unavailable.
     */
    void incrErrorCount() {
        long count = errorCount.incrementAndGet();
        if (count >= fusingThreshold && state.isAliveState()) {
            synchronized (this) {
                count = errorCount.longValue();
                if (count >= fusingThreshold && state.isAliveState()) {
                    log.error("NettyClient unavailable Error: url={} {}",
                            url.getIdentity(), url.getHostPort());
                    state = ChannelState.UNALIVE;
                }
            }
        }
    }

    /**
     * Reset the consecutive error count and recover to available state if applicable.
     */
    void resetErrorCount() {
        errorCount.set(0);

        if (state.isAliveState()) {
            return;
        }

        synchronized (this) {
            if (state.isAliveState()) {
                return;
            }

            if (state.isUnAliveState()) {
                long count = errorCount.longValue();
                if (count < fusingThreshold) {
                    state = ChannelState.ALIVE;
                    log.info("NettyClient recover available: url={} {}",
                            url.getIdentity(), url.getHostPort());
                }
            }
        }
    }

    /**
     * Register a callback for an async request and schedule a per-request timeout.
     * Rejects the request if the concurrent count exceeds the limit to prevent OOM.
     *
     * @param requestId      the request ID
     * @param responseFuture the future to complete when the response arrives
     */
    public void registerCallback(long requestId, ResponseFuture responseFuture) {
        if (this.callbackMap.size() >= JawsConstants.NETTY_CLIENT_MAX_REQUEST) {
            // reject request, prevent from OutOfMemoryError
            throw new JawsServiceException("NettyClient over of max concurrent request, drop request, url: "
                    + url.getUri() + " requestId=" + requestId, JawsErrorMsgConstants.SERVICE_REJECT);
        }

        this.callbackMap.put(requestId, responseFuture);

        // Schedule a one-shot timeout task for this request
        int timeout = responseFuture.getTimeout();
        if (timeout > 0) {
            Timeout timerTimeout = timeoutTimer.newTimeout(t -> {
                ResponseFuture future = callbackMap.remove(requestId);
                if (future != null) {
                    timeoutMap.remove(requestId);
                    try {
                        future.cancel();
                    } catch (Exception e) {
                        log.error("timeout cancel error: uri={} requestId={}", url.getUri(), requestId, e);
                    }
                }
            }, timeout, TimeUnit.MILLISECONDS);
            timeoutMap.put(requestId, timerTimeout);
        }
    }

    public ResponseFuture removeCallback(long requestId) {
        // Cancel the timeout task if still pending
        Timeout timeout = timeoutMap.remove(requestId);
        if (timeout != null) {
            timeout.cancel();
        }
        return callbackMap.remove(requestId);
    }
}