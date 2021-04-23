package org.hongxi.jaws.proxy.support;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.proxy.ProxyFactory;
import org.hongxi.jaws.proxy.RefererCommonHandler;

import java.util.List;

/**
 * common proxy
 *
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "common")
public class CommonProxyFactory implements ProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> clz, List<Cluster<T>> clusters) {
        return (T) new RefererCommonHandler(clusters.get(0).getUrl().getPath(), clusters);
    }
}