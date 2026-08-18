package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.cluster.Router;
import org.hongxi.jaws.cluster.router.RouterChain;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public abstract class AbstractLoadBalance<T> implements LoadBalance<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractLoadBalance.class);

    public static final int MAX_REFERENCE_COUNT = 10;
    private List<Reference<T>> references;
    private volatile RouterChain<T> routerChain;

    @Override
    public void onRefresh(List<Reference<T>> references) {
        Collections.shuffle(references);
        // Reference replacement only; in-place update of references is not allowed.
        this.references = references;
    }

    @Override
    public Reference<T> select(Request request) {
        List<Reference<T>> references = applyRouter(this.references, request);
        if (references == null) {
            throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call request:" + request);
        }
        Reference<T> ref = null;
        if (references.size() > 1) {
            ref = doSelect(request);

        } else if (references.size() == 1) {
            ref = references.get(0).isAvailable() ? references.get(0) : null;
        }

        if (ref != null) {
            return ref;
        }
        throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call request:" + request);
    }

    @Override
    public void selectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = applyRouter(this.references, request);

        if (references == null) {
            throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call : references_size= 0 "
                    + JawsFrameworkUtils.toString(request));
        }

        if (references.size() > 1) {
            doSelectToHolder(request, refersHolder);

        } else if (references.size() == 1 && references.get(0).isAvailable()) {
            refersHolder.add(references.get(0));
        }
        if (refersHolder.isEmpty()) {
            throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call : references_size="
                    + references.size() + " " + JawsFrameworkUtils.toString(request));
        }
    }

    protected List<Reference<T>> getReferences() {
        return references;
    }

    protected abstract Reference<T> doSelect(Request request);

    protected abstract void doSelectToHolder(Request request, List<Reference<T>> refersHolder);

    /**
     * Set the router chain for filtering references before selection.
     */
    public void setRouterChain(RouterChain<T> routerChain) {
        this.routerChain = routerChain;
    }

    /**
     * Add a single router to the chain.
     */
    public void addRouter(Router<T> router) {
        if (this.routerChain == null) {
            synchronized (this) {
                if (this.routerChain == null) {
                    this.routerChain = new RouterChain<>();
                }
            }
        }
        this.routerChain.addRouter(router);
    }

    /**
     * Apply the router chain to filter references.
     * Returns the original list if no router chain is configured.
     */
    private List<Reference<T>> applyRouter(List<Reference<T>> references, Request request) {
        if (references == null || references.isEmpty()) {
            return references;
        }
        RouterChain<T> chain = this.routerChain;
        if (chain == null || !chain.hasRouters()) {
            return references;
        }
        return chain.route(references, request);
    }

    /**
     * Calculate warm-up weight for a reference based on its startup timestamp.
     * <p>
     * A newly started provider gets weight 0, which linearly increases to
     * {@code defaultWeight} over the configured warm-up period.
     * If the provider has been running longer than the warm-up period, full weight is returned.
     *
     * @param ref          the reference to calculate weight for
     * @param defaultWeight the full weight value (returned when warm-up is complete)
     * @return effective weight in range [0, defaultWeight]
     */
    protected int getWarmupWeight(Reference<T> ref, int defaultWeight) {
        URL serviceUrl = ref.getServiceUrl();
        if (serviceUrl == null) {
            return defaultWeight;
        }
        int warmup = serviceUrl.getParameter(
                URLParamType.warmup.getName(), URLParamType.warmup.intValue());
        if (warmup <= 0) {
            return defaultWeight;
        }
        long timestamp = serviceUrl.getParameter(
                URLParamType.timestamp.getName(), 0L);
        if (timestamp <= 0) {
            return defaultWeight;
        }
        long runningTime = System.currentTimeMillis() - timestamp;
        if (runningTime >= warmup) {
            return defaultWeight;
        }
        if (runningTime <= 0) {
            return 1;
        }
        int weight = (int) ((double) runningTime / warmup * defaultWeight);
        return Math.max(1, weight);
    }
}