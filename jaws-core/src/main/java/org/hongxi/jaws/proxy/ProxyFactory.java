package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.extension.Spi;

import java.util.List;

/**
 * SPI for creating client-side proxies bound to one or more
 * {@link Cluster} instances, so that interface method calls are routed
 * as remote invocations. Implementations must be stateless and thread-safe,
 * since one singleton instance per extension name is shared by all references.
 * <p>
 * Built-in implementations include a JDK dynamic proxy factory and a
 * generic-invocation factory.
 *
 * @see JdkProxyFactory
 * @see ReferenceInvocationHandler
 *
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@Spi(singleton = true)
public interface ProxyFactory {

    <T> T getProxy(Class<T> interfaceClass, List<Cluster<T>> clusters);
}