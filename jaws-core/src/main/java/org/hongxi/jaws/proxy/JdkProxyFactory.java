package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.extension.SpiMeta;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * jdk proxy
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "jdk")
public class JdkProxyFactory implements ProxyFactory {

    @Override
    public <T> T getProxy(Class<T> interfaceClass, List<Cluster<T>> clusters) {
        // noinspection unchecked
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                new ReferenceInvocationHandler<>(interfaceClass, clusters)
        );
    }
}