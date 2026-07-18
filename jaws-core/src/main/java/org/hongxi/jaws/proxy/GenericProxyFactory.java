package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.GenericService;
import org.hongxi.jaws.rpc.URL;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Proxy factory for generic invocation.
 * <p>
 * Creates a JDK dynamic proxy for {@link GenericService} that delegates to
 * {@link GenericReferenceInvocationHandler} for building generic requests.
 * <p>
 * Created by shenhongxi on 2026/7/18.
 */
@SpiMeta(name = "generic")
public class GenericProxyFactory implements ProxyFactory {

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getProxy(Class<T> clazz, List<Cluster<T>> clusters) {
        // Resolve the real interface name from the cluster URL's serviceInterface parameter.
        // clazz is GenericService here, so clazz.getName() would give the wrong serviceKey.
        String realInterfaceName = resolveRealInterfaceName(clusters);
        if (realInterfaceName == null) {
            realInterfaceName = clazz.getName();
        }
        GenericReferenceInvocationHandler<T> handler =
                new GenericReferenceInvocationHandler<>(realInterfaceName, clusters);
        return (T) Proxy.newProxyInstance(
                GenericService.class.getClassLoader(),
                new Class<?>[]{GenericService.class},
                handler);
    }

    private <T> String resolveRealInterfaceName(List<Cluster<T>> clusters) {
        if (clusters != null) {
            for (Cluster<T> cluster : clusters) {
                URL url = cluster.getUrl();
                String si = url.getParameter("serviceInterface");
                if (si != null && !si.isEmpty()) {
                    return si;
                }
            }
        }
        return null;
    }
}
