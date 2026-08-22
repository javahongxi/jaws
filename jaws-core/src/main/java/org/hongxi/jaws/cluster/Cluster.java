package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Cluster is the service broker that handles load balancing, fault tolerance,
 * and request routing. Each implementation encapsulates a specific fault-tolerance
 * strategy (failover, failfast, failback, etc.).
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Cluster<T> extends Caller<T> {

    @Override
    void init();

    void setUrl(URL url);

    void onRefresh(List<Reference<T>> references);

    List<Reference<T>> getReferences();

    void setLoadBalance(LoadBalance<T> loadBalance);

    LoadBalance<T> getLoadBalance();
}