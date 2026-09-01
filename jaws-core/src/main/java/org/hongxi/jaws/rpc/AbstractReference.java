package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.util.RpcUtils;
import org.hongxi.jaws.exception.JawsServiceException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base implementation of {@link Reference} providing availability checking and
 * per-endpoint call statistics: an {@link java.util.concurrent.atomic.AtomicInteger
 * AtomicInteger} tracks in-flight calls, while atomic counters accumulate successful
 * call count and elapsed time. Both feed load-balancing strategies such as leastActive
 * and shortestResponse.
 * <p>
 * Subclasses implement {@link #doCall(Request)} to perform the actual remote
 * invocation.
 *
 * <p>Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractReference<T> extends AbstractEndpoint implements Reference<T> {

    protected Class<T> interfaceClass;

    /**
     * Current number of in-flight RPC calls on this reference (endpointType=reference).
     * Incremented before each invocation and decremented after the response is received
     * (or after the async future completes). Consumed by load-balancing strategies
     * such as leastActive and shortestResponse to estimate real-time endpoint load.
     */
    protected AtomicInteger activeReferenceCount = new AtomicInteger(0);

    /**
     * Cumulative response time statistics for successful calls, used for shortest-response load balancing
     */
    private final AtomicLong succeededElapsed = new AtomicLong(0);
    private final AtomicLong succeededCount = new AtomicLong(0);

    public AbstractReference(Class<T> interfaceClass, URL url) {
        super(url);
        this.interfaceClass = interfaceClass;
    }

    @Override
    public Class<T> getInterface() {
        return interfaceClass;
    }

    @Override
    public Response call(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException(this.getClass().getSimpleName() + " call failed: endpoint is not available, url=" + url.getUri()
                    + " " + RpcUtils.toString(request));
        }

        incrActiveCount();
        Response response = null;
        long startTime = System.nanoTime();
        try {
            response = doCall(request);
            return response;
        } finally {
            decrActiveCount(response);
            if (response != null && response.getException() == null) {
                long elapsed = System.nanoTime() - startTime;
                succeededElapsed.addAndGet(elapsed);
                succeededCount.incrementAndGet();
            }
        }
    }

    @Override
    public CompletableFuture<Response> callAsync(Request request) {
        if (!isAvailable()) {
            return CompletableFuture.failedFuture(new JawsServiceException(
                    this.getClass().getSimpleName() + " callAsync failed: endpoint is not available, url=" + url.getUri()
                            + " " + RpcUtils.toString(request)));
        }

        incrActiveCount();
        long startTime = System.nanoTime();
        Response response;
        try {
            response = doCall(request);
        } catch (Exception e) {
            decrActiveCount(null);
            return CompletableFuture.failedFuture(e);
        }

        // Bridge DefaultResponseFuture → CompletableFuture so that the returned
        // future completes only when the actual response is ready, not when
        // doCall() returns the future handle.
        if (response instanceof ResponseFuture rf && !rf.isDone()) {
            CompletableFuture<Response> future = new CompletableFuture<>();
            rf.addListener(f -> {
                decrActiveCount(response);
                if (response.getException() == null) {
                    long elapsed = System.nanoTime() - startTime;
                    succeededElapsed.addAndGet(elapsed);
                    succeededCount.incrementAndGet();
                }
                future.complete(response);
            });
            return future;
        }

        decrActiveCount(response);
        if (response.getException() == null) {
            long elapsed = System.nanoTime() - startTime;
            succeededElapsed.addAndGet(elapsed);
            succeededCount.incrementAndGet();
        }
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public int activeReferenceCount() {
        return activeReferenceCount.get();
    }

    protected void incrActiveCount() {
        activeReferenceCount.incrementAndGet();
    }

    protected void decrActiveCount(Response response) {
        activeReferenceCount.decrementAndGet();
    }

    protected abstract Response doCall(Request request);

    @Override
    public String desc() {
        return "[" + this.getClass().getSimpleName() + "] url=" + url;
    }

    @Override
    public URL getServiceUrl() {
        return url;
    }

    public long getSucceededElapsed() {
        return succeededElapsed.get();
    }

    public long getSucceededCount() {
        return succeededCount.get();
    }
}