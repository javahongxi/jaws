package org.hongxi.jaws.transport;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.ResponseFuture;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base implementation of {@link Client} holding state shared by all
 * client transports: the remote {@link URL}, the volatile {@link ChannelState}
 * lifecycle flag, the async-request callback bookkeeping (callback futures
 * plus one-shot per-request timeouts on a shared HashedWheelTimer), and the
 * error-fusing availability management.
 * <p>
 * Also provides the graceful-close template: drain in-flight requests within
 * the given timeout, cancel whatever remains, then delegate transport-specific
 * teardown to {@link #doClose()}.
 * <p>
 * Concrete transports such as {@link org.hongxi.jaws.transport.netty.NettyClient}
 * and {@link org.hongxi.jaws.transport.http2.Http2Client} extend this class to
 * provide connection management and request sending.
 * <p>
 * Created by shenhongxi on 2020/7/28.
 */
public abstract class AbstractClient implements Client {
    private static final Logger log = LoggerFactory.getLogger(AbstractClient.class);

    /**
     * Max number of in-flight requests per client, rejecting overflow
     * requests to prevent OutOfMemoryError.
     */
    private static final int MAX_INFLIGHT_REQUESTS = 20000;

    /**
     * Per-request timeout scheduler shared by all clients.
     * Each callback registers a one-shot timeout task at registration time.
     */
    private static final HashedWheelTimer timeoutTimer = new HashedWheelTimer(
            new io.netty.util.concurrent.DefaultThreadFactory("jaws-client-timeout", true),
            30, TimeUnit.MILLISECONDS);

    protected final URL url;

    protected volatile ChannelState state = ChannelState.UNINIT;

    private final int fusingThreshold;
    /** Consecutive error count for client-side error fusing. */
    private final AtomicLong errorCount = new AtomicLong(0);

    /**
     * Async requests need to register a callback future.
     * Removal triggers: 1) response received from server  2) timeout task cancels it  3) close().
     */
    private final ConcurrentMap<Long, ResponseFuture> callbackMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Timeout> timeoutMap = new ConcurrentHashMap<>();

    protected AbstractClient(URL url) {
        this.url = url;
        this.fusingThreshold = url.getIntParameter(UrlParam.Client.FUSING_THRESHOLD);
    }

    /**
     * Increment the consecutive error count.
     * If the count reaches the fusing threshold, mark this client as unavailable.
     */
    public void incrErrorCount() {
        long count = errorCount.incrementAndGet();
        if (count >= fusingThreshold && state.isAliveState()) {
            synchronized (this) {
                count = errorCount.longValue();
                if (count >= fusingThreshold && state.isAliveState()) {
                    log.error("{} marked unavailable due to consecutive errors: url={} {}",
                            getClass().getSimpleName(), url.getIdentity(), url.getHostPort());
                    state = ChannelState.UNALIVE;
                }
            }
        }
    }

    /**
     * Reset the consecutive error count and recover to available state if applicable.
     */
    public void resetErrorCount() {
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
                    log.info("{} recovered to available: url={} {}",
                            getClass().getSimpleName(), url.getIdentity(), url.getHostPort());
                }
            }
        }
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }

    /**
     * Register a callback for an async request and schedule a per-request timeout.
     * Rejects the request if the concurrent count exceeds the limit to prevent OOM.
     *
     * @param requestId      the request ID
     * @param responseFuture the future to complete when the response arrives
     */
    public void registerCallback(long requestId, ResponseFuture responseFuture) {
        if (callbackMap.size() >= MAX_INFLIGHT_REQUESTS) {
            // reject request, prevent from OutOfMemoryError
            throw new JawsServiceException(getClass().getSimpleName()
                    + " exceeded max concurrent requests, request rejected, url: "
                    + url.getUri() + " requestId=" + requestId, JawsErrorCode.SERVICE_REJECT);
        }

        callbackMap.put(requestId, responseFuture);

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
                        log.error("failed to cancel timeout task: uri={} requestId={}", url.getUri(), requestId, e);
                    }
                }
            }, timeout, TimeUnit.MILLISECONDS);
            timeoutMap.put(requestId, timerTimeout);
        }
    }

    /**
     * Remove the callback future for the given request, cancelling its
     * pending timeout task if any.
     */
    public ResponseFuture removeCallback(long requestId) {
        // Cancel the timeout task if still pending
        Timeout timeout = timeoutMap.remove(requestId);
        if (timeout != null) {
            timeout.cancel();
        }
        return callbackMap.remove(requestId);
    }

    @Override
    public void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        if (state.isCloseState()) {
            return;
        }

        try {
            // Graceful drain: keep the connection open and give in-flight
            // requests a chance to complete within the given timeout
            if (timeout > 0) {
                awaitPendingRequests(timeout);
            }
            cleanup();
            if (state.isUnInitState()) {
                log.info("{} close skipped: never opened, url={}",
                        getClass().getSimpleName(), url.getUri());
                return;
            }

            // Set close state
            state = ChannelState.CLOSE;
            log.info("{} closed successfully: url={}", getClass().getSimpleName(), url.getUri());
        } catch (Exception e) {
            log.error("{} failed to close: url={}", getClass().getSimpleName(), url.getUri(), e);
        }
    }

    /**
     * Release transport-specific resources. Callbacks and timeout tasks
     * have already been drained by the close template.
     */
    protected abstract void doClose();

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
        doClose();
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
            log.warn("{} closed while {} pending requests not completed: url={}",
                    getClass().getSimpleName(), callbackMap.size(), url.getUri());
        }
    }
}
