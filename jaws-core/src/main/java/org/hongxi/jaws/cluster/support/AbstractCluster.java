package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.ReferenceDestroyer;
import org.hongxi.jaws.rpc.URL;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base implementation of {@link Cluster} providing common lifecycle,
 * reference management, and load balance wiring.
 * <p>
 * Subclasses implement {@link #call} to define the fault-tolerance
 * strategy (failover, failfast, failback, etc.).
 *
 * @see FailoverCluster
 * @see FailfastCluster
 * @see FailbackCluster
 */
public abstract class AbstractCluster<T> implements Cluster<T> {

    protected URL url;
    // volatile: written under the instance lock in onRefresh, read lock-free
    // by destroy/getReferences/getInterface/toString
    protected volatile List<Reference<T>> references = new ArrayList<>();
    protected LoadBalance<T> loadBalance;
    protected final AtomicBoolean available = new AtomicBoolean(false);

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
    public synchronized void destroy() {
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
    public void setLoadBalance(LoadBalance<T> loadBalance) {
        this.loadBalance = loadBalance;
    }

    @Override
    public LoadBalance<T> getLoadBalance() {
        return loadBalance;
    }

    @Override
    public Class<T> getInterface() {
        if (CollectionUtils.isEmpty(references)) {
            return null;
        }
        return references.get(0).getInterface();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "loadbalance=" + loadBalance +
                ", references=" + references + "}";
    }
}
