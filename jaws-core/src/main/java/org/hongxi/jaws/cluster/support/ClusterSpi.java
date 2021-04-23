package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.HaStrategy;
import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "default")
public class ClusterSpi<T> implements Cluster<T> {

    private HaStrategy<T> haStrategy;

    private LoadBalance<T> loadBalance;

    private List<Referer<T>> referers;

    private AtomicBoolean available = new AtomicBoolean(false);

    private URL url;

    @Override
    public void init() {
        onRefresh(referers);
        available.set(true);
    }

    @Override
    public Class<T> getInterface() {
        if (referers == null || referers.isEmpty()) {
            return null;
        }

        return referers.get(0).getInterface();
    }

    @Override
    public Response call(Request request) {
        if (available.get()) {
            try {
                return haStrategy.call(request, loadBalance);
            } catch (Exception e) {
                return callFalse(request, e);
            }
        }
        throw new JawsServiceException(String.format("ClusterSpi Call false for request: %s, ClusterSpi not created or destroyed", request),
                JawsErrorMsgConstants.SERVICE_NOT_FOUND, false);
    }

    @Override
    public String desc() {
        return toString();
    }

    @Override
    public void destroy() {
        available.set(false);
        for (Referer<T> referer : this.referers) {
            referer.destroy();
        }
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void setUrl(URL url) {
        this.url = url;
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    @Override
    public String toString() {
        return "cluster: {" + "ha=" + haStrategy + ",loadbalance=" + loadBalance +
                "referers=" + referers + "}";

    }

    @Override
    public synchronized void onRefresh(List<Referer<T>> referers) {
        if (CollectionUtils.isEmpty(referers)) {
            return;
        }

        loadBalance.onRefresh(referers);
        List<Referer<T>> oldReferers = this.referers;
        this.referers = referers;
        haStrategy.setUrl(getUrl());

        if (oldReferers == null || oldReferers.isEmpty()) {
            return;
        }

        List<Referer<T>> delayDestroyReferers = new ArrayList<Referer<T>>();

        for (Referer<T> referer : oldReferers) {
            if (referers.contains(referer)) {
                continue;
            }

            delayDestroyReferers.add(referer);
        }

        if (!delayDestroyReferers.isEmpty()) {
            RefererSupports.delayDestroy(delayDestroyReferers);
        }
    }

    public AtomicBoolean getAvailable() {
        return available;
    }

    public void setAvailable(AtomicBoolean available) {
        this.available = available;
    }

    public HaStrategy<T> getHaStrategy() {
        return haStrategy;
    }

    @Override
    public void setHaStrategy(HaStrategy<T> haStrategy) {
        this.haStrategy = haStrategy;
    }

    @Override
    public LoadBalance<T> getLoadBalance() {
        return loadBalance;
    }

    @Override
    public void setLoadBalance(LoadBalance<T> loadBalance) {
        this.loadBalance = loadBalance;
    }

    @Override
    public List<Referer<T>> getReferers() {
        return referers;
    }

    protected Response callFalse(Request request, Exception cause) {

        // biz exception 无论如何都要抛出去
        if (ExceptionUtils.isBizException(cause)) {
            throw (RuntimeException) cause;
        }

        // 其他异常根据配置决定是否抛，如果抛异常，需要统一为 JawsException
        if (Boolean.parseBoolean(getUrl().getParameter(URLParamType.throwException.getName(), URLParamType.throwException.value()))) {
            if (cause instanceof JawsAbstractException) {
                throw (JawsAbstractException) cause;
            } else {
                JawsServiceException jawsException =
                        new JawsServiceException(String.format("ClusterSpi Call false for request: %s", request), cause);
                throw jawsException;
            }
        }

        return JawsFrameworkUtils.buildErrorResponse(request, cause);
    }

}