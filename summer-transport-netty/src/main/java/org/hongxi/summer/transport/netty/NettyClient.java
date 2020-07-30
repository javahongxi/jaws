package org.hongxi.summer.transport.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import org.hongxi.summer.common.SummerConstants;
import org.hongxi.summer.common.URLParamType;
import org.hongxi.summer.exception.SummerAbstractException;
import org.hongxi.summer.exception.SummerServiceException;
import org.hongxi.summer.rpc.*;
import org.hongxi.summer.transport.AbstractSharedPoolClient;
import org.hongxi.summer.transport.Channel;
import org.hongxi.summer.transport.SharedObjectFactory;
import org.hongxi.summer.transport.TransportException;
import org.hongxi.summer.util.SummerFrameworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by shenhongxi on 2020/7/28.
 */
public class NettyClient extends AbstractSharedPoolClient {
    private static final Logger logger = LoggerFactory.getLogger(AbstractSharedPoolClient.class);

    private static final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();
    /**
     * 回收过期任务
     */
    private static ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    /**
     * 异步的request，需要注册callback future
     * 触发remove的操作有： 1) service的返回结果处理。 2) timeout thread cancel
     */
    protected ConcurrentMap<Long, ResponseFuture> callbackMap = new ConcurrentHashMap<>();
    private ScheduledFuture<?> timeMonitorFuture;
    private Bootstrap bootstrap;
    private int fusingThreshold;
    /**
     * 连续失败次数
     */
    private AtomicLong errorCount = new AtomicLong(0);

    public NettyClient(URL url) {
        super(url);
        fusingThreshold = url.getIntParameter(URLParamType.fusingThreshold.getName(), URLParamType.fusingThreshold.intValue());
        timeMonitorFuture = scheduledExecutor.scheduleWithFixedDelay(
                new TimeoutMonitor("timeout_monitor_" + url.getHost() + "_" + url.getPort()),
                SummerConstants.NETTY_TIMEOUT_TIMER_PERIOD, SummerConstants.NETTY_TIMEOUT_TIMER_PERIOD,
                TimeUnit.MILLISECONDS);
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    @Override
    protected SharedObjectFactory createChannelFactory() {
        return new NettyChannelFactory(this);
    }

    @Override
    public Response request(Request request) throws TransportException {
        if (!isAvailable()) {
            throw new SummerServiceException("NettyChannel is unavailable: url="
                    + url.getUri() + SummerFrameworkUtils.toString(request));
        }
        boolean isAsync = false;
        Object async = RpcContext.getContext().getAttribute(SummerConstants.ASYNC_SUFFIX);
        if (async != null && async instanceof Boolean) {
            isAsync = (Boolean) async;
        }
        return request(request, isAsync);
    }

    private Response request(Request request, boolean async) throws TransportException {
        Channel channel;
        Response response;
        try {
            // return channel or throw exception(timeout or connection_fail)
            channel = getChannel();

            if (channel == null) {
                logger.error("borrowObject null: url={} {}", url.getUri(),
                        SummerFrameworkUtils.toString(request));
                return null;
            }

            // async request
            response = channel.request(request);
        } catch (Exception e) {
            logger.error("request Error: url={} {}, {}", url.getUri(),
                    SummerFrameworkUtils.toString(request), e.getMessage());

            if (e instanceof SummerAbstractException) {
                throw (SummerAbstractException) e;
            } else {
                throw new SummerServiceException("NettyClient request Error: url=" +
                        url.getUri() + " " + SummerFrameworkUtils.toString(request), e);
            }
        }

        // aysnc or sync result
        response = asyncResponse(response, async);

        return response;
    }

    /**
     * 如果async是false，那么同步获取response的数据
     *
     * @param response
     * @param async
     * @return
     */
    private Response asyncResponse(Response response, boolean async) {
        if (async || !(response instanceof ResponseFuture)) {
            return response;
        }
        return new DefaultResponse(response);
    }

    @Override
    public synchronized boolean open() {
        return false;
    }

    @Override
    public synchronized void close() {

    }

    @Override
    public synchronized void close(int timeout) {

    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public URL getUrl() {
        return null;
    }

    public ResponseFuture removeCallback(long requestId) {
        return callbackMap.remove(requestId);
    }

    /**
     * 回收超时任务
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
                    logger.error("{} clear timeout future Error: uri={} requestId={}",
                            name, url.getUri(), entry.getKey(), e);
                }
            }
        }
    }
}
