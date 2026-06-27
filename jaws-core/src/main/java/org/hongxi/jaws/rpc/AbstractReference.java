package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractReference<T> extends AbstractNode implements Reference<T> {

    protected Class<T> clazz;
    protected AtomicInteger activeReferenceCount = new AtomicInteger(0);
    protected URL serviceUrl;

    public AbstractReference(Class<T> clazz, URL url) {
        super(url);
        this.clazz = clazz;
        this.serviceUrl = url;
    }

    public AbstractReference(Class<T> clazz, URL url, URL serviceUrl) {
        super(url);
        this.clazz = clazz;
        this.serviceUrl = serviceUrl;
    }

    @Override
    public Class<T> getInterface() {
        return clazz;
    }

    @Override
    public Response call(Request request) {
        if (!isAvailable()) {
            throw new JawsFrameworkException(this.getClass().getSimpleName() + " call Error: node is not available, url=" + url.getUri()
                    + " " + JawsFrameworkUtils.toString(request));
        }

        incrActiveCount(request);
        Response response = null;
        try {
            response = doCall(request);

            return response;
        } finally {
            decrActiveCount(request, response);
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
        return serviceUrl;
    }

}