package org.hongxi.jaws.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by shenhongxi on 2020/7/28.
 */
public class NettyClient extends AbstractClient {
    private static final Logger log = LoggerFactory.getLogger(NettyClient.class);

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();
    /**
     * Recycle expired tasks.
     */
    private static final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    /**
     * Async requests need to register a callback future.
     * Removal triggers: 1) response received from server  2) timeout monitor cancels it.
     */
    protected ConcurrentMap<Long, ResponseFuture> callbackMap = new ConcurrentHashMap<>();
    private final ScheduledFuture<?> timeMonitorFuture;
    private Bootstrap bootstrap;
    private final int fusingThreshold;
    /**
     * Consecutive error count.
     */
    private final AtomicLong errorCount = new AtomicLong(0);
    private NettyChannel channel;

    public NettyClient(URL url) {
        super(url);
        fusingThreshold = url.getParameter(URLParamType.fusingThreshold.getName(),
                URLParamType.fusingThreshold.intValue());
        timeMonitorFuture = scheduledExecutor.scheduleWithFixedDelay(
                new TimeoutMonitor("timeout_monitor_" + url.getHost() + "_" + url.getPort()),
                JawsConstants.NETTY_TIMEOUT_TIMER_PERIOD, JawsConstants.NETTY_TIMEOUT_TIMER_PERIOD,
                TimeUnit.MILLISECONDS);
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
        boolean isAsync = false;
        Object async = RpcContext.getContext().getAttribute(JawsConstants.ASYNC_FLAG);
        if (async instanceof Boolean b) {
            isAsync = b;
        }
        return request(request, isAsync);
    }

    private Response request(Request request, boolean async) {
        Response response;
        try {
            if (!channel.isAvailable()) {
                channel.reconnect();
            }
            if (!channel.isAvailable()) {
                throw new JawsServiceException("NettyChannel is not available: url="
                        + url.getUri() + JawsFrameworkUtils.toString(request));
            }
            // async request
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

        // async or sync result
        response = asyncResponse(response, async);

        return response;
    }

    /**
     * If async is false, block and wait for the response data.
     *
     * @param response the response future
     * @param async    whether the call is asynchronous
     * @return the resolved response
     */
    private Response asyncResponse(Response response, boolean async) {
        if (async || !(response instanceof ResponseFuture)) {
            return response;
        }
        return new DefaultResponse(response);
    }

    @Override
    public synchronized boolean open() {
        if (isAvailable()) {
            return true;
        }

        int timeout = getUrl().getParameter(URLParamType.connectTimeout.getName(),
                URLParamType.connectTimeout.intValue());
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

    public void cleanup() {
        // Cancel the timeout monitor
        timeMonitorFuture.cancel(true);
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

    public ResponseFuture removeCallback(long requestId) {
        return callbackMap.remove(requestId);
    }

    /**
     * Increment the consecutive error count.
     * If the count reaches the fusing threshold, mark this client as unavailable.
     */
    void incrErrorCount() {
        long count = errorCount.incrementAndGet();

        // If the node is available and consecutive failures exceed the fusing threshold, mark it as unavailable.
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

            // If the node is unalive, attempt to recover; ignore if close or uninit.
            if (state.isUnAliveState()) {
                long count = errorCount.longValue();

                // Double-check after concurrent errorCount update
                if (count < fusingThreshold) {
                    state = ChannelState.ALIVE;
                    log.info("NettyClient recover available: url={} {}",
                            url.getIdentity(), url.getHostPort());
                }
            }
        }
    }

    /**
     * Register a callback for an async request.
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
    }

    /**
     * Cancel timed-out tasks.
     */
    class TimeoutMonitor implements Runnable {
        private String name;

        public TimeoutMonitor(String name) {
            this.name = name;
        }

        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            for (Map.Entry<Long, ResponseFuture> entry : callbackMap.entrySet()) {
                try {
                    ResponseFuture future = entry.getValue();

                    if (future.getCreateTime() + future.getTimeout() < currentTime) {
                        // timeout: remove from callback list, and then cancel
                        removeCallback(entry.getKey());
                        future.cancel();
                    }
                } catch (Exception e) {
                    log.error("{} clear timeout future Error: uri={} requestId={}",
                            name, url.getUri(), entry.getKey(), e);
                }
            }
        }
    }
}