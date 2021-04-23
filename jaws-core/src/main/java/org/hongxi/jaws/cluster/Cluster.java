package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Caller;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Cluster is a service broker
 * 
 * Created by shenhongxi on 2021/4/23.
 */
@Spi(scope = Scope.PROTOTYPE)
public interface Cluster<T> extends Caller<T> {

    @Override
    void init();

    void setUrl(URL url);

    void setLoadBalance(LoadBalance<T> loadBalance);

    LoadBalance<T> getLoadBalance();

    void setHaStrategy(HaStrategy<T> haStrategy);

    void onRefresh(List<Referer<T>> referers);

    List<Referer<T>> getReferers();
}