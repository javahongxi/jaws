package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by shenhongxi on 2021/4/23.
 */
@Extension("default")
public class DefaultCluster<T> implements Cluster<T> {

    private URL url;

    private List<Reference<T>> references = new ArrayList<>();

    private HaStrategy<T> haStrategy;

    private LoadBalance<T> loadBalance;

    private final AtomicBoolean available = new AtomicBoolean(false);

    @Override
    public void init() {
        // onRefresh is already triggered by Directory during directory.init()
        // via the change listener mechanism, so no need to call it again here.
        available.set(true);
    }

    @Override
    public synchronized void onRefresh(List<Reference<T>> references) {
        if (CollectionUtils.isEmpty(references)) {
            return;
        }

        haStrategy.setUrl(getUrl());
        loadBalance.onRefresh(references);
        List<Reference<T>> oldReferences = this.references;
        this.references = references;

        if (CollectionUtils.isEmpty(oldReferences)) {
            return;
        }

        ReferenceDestroyer.delayDestroy(
                oldReferences.stream().filter(r -> !references.contains(r)).toList()
        );
    }

    @Override
    public Class<T> getInterface() {
        if (CollectionUtils.isEmpty(references)) {
            return null;
        }
        return references.get(0).getInterface();
    }

    @Override
    public Response call(Request request) {
        if (!available.get()) {
            throw new JawsServiceException("Cluster not available, interface=" + getInterface(),
                    JawsErrorCode.SERVICE_NOT_FOUND, false);
        }

        try {
            return haStrategy.call(request, loadBalance);
        } catch (Exception e) {
            if (ExceptionUtils.isBizException(e)) {
                throw (RuntimeException) e;
            }
            if (!getUrl().getBoolParameter(UrlParam.Client.THROW_EXCEPTION)) {
                return JawsFrameworkUtils.buildErrorResponse(request, e);
            }
            if (e instanceof JawsAbstractException jae) {
                throw jae;
            }
            throw new JawsServiceException("Cluster call failed, request=" + request, e);
        }
    }

    @Override
    public void destroy() {
        available.set(false);
        List<Reference<T>> references = this.references;
        if (references != null) {
            for (Reference<T> reference : references) {
                reference.destroy();
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    @Override
    public String desc() {
        return toString();
    }

    @Override
    public void setUrl(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public List<Reference<T>> getReferences() {
        return references;
    }

    @Override
    public void setHaStrategy(HaStrategy<T> haStrategy) {
        this.haStrategy = haStrategy;
    }

    @Override
    public void setLoadBalance(LoadBalance<T> loadBalance) {
        this.loadBalance = loadBalance;
    }

    @Override
    public LoadBalance<T> getLoadBalance() {
        return loadBalance;
    }

    @Override
    public String toString() {
        return "cluster: {" + "ha=" + haStrategy +
                ",loadbalance=" + loadBalance +
                "references=" + references + "}";
    }
}