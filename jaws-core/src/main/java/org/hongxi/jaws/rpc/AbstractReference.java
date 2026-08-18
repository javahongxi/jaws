package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractReference<T> extends AbstractNode implements Reference<T> {

    protected Class<T> interfaceClass;
    protected AtomicInteger activeReferenceCount = new AtomicInteger(0);

    /* Cumulative response time statistics for successful calls, used for shortest-response load balancing */
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
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " call Error: node is not available, url=" + url.getUri()
                    + " " + JawsFrameworkUtils.toString(request));
        }

        incrActiveCount(request);
        Response response = null;
        long startTime = System.nanoTime();
        try {
            response = doCall(request);
            return response;
        } finally {
            decrActiveCount(request, response);
            if (response != null && response.getException() == null) {
                long elapsed = System.nanoTime() - startTime;
                succeededElapsed.addAndGet(elapsed);
                succeededCount.incrementAndGet();
            }
        }
    }

    @Override
    public int activeReferenceCount() {
        return activeReferenceCount.get();
    }

    protected void incrActiveCount(Request request) {
        activeReferenceCount.incrementAndGet();
    }

    protected void decrActiveCount(Request request, Response response) {
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

    /*
     * 获取成功调用的累计响应时间（纳秒）
     */
    public long getSucceededElapsed() {
        return succeededElapsed.get();
    }

    /*
     * 获取成功调用次数
     */
    public long getSucceededCount() {
        return succeededCount.get();
    }

}