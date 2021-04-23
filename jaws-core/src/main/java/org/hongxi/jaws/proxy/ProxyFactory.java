package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.extension.Spi;

import java.util.List;

/**
 * Created by shenhongxi on 2021/4/23.
 */
@Spi
public interface ProxyFactory {

    <T> T getProxy(Class<T> clz, List<Cluster<T>> clusters);
}