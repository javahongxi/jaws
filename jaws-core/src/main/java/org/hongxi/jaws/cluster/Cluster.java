package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Reference;

import java.util.List;

/**
 * Cluster is the service broker that handles load balancing, fault tolerance,
 * and request routing. Each implementation encapsulates a specific fault-tolerance
 * strategy (failover, failfast, etc.).
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@Spi
public interface Cluster<T> extends Caller<T> {

    @Override
    void init();

    void onRefresh(List<Reference<T>> references);

    List<Reference<T>> getReferences();
}