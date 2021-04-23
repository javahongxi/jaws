package org.hongxi.jaws.proxy.support;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.proxy.ProxyFactory;
import org.hongxi.jaws.proxy.RefererInvocationHandler;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * jdk proxy
 *
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "jdk")
public class JdkProxyFactory implements ProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> clz, List<Cluster<T>> clusters) {
        return (T) Proxy.newProxyInstance(clz.getClassLoader(), new Class[]{clz}, new RefererInvocationHandler<>(clz, clusters));
    }
}