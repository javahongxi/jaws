package org.hongxi.jaws.config.deploy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.support.ClusterSupport;
import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Service deployer that handles export/unexport for provider side
 * and cluster building/refer for consumer side.
 * <p>
 * Created by shenhongxi on 2021/3/6.
 */
@Spi(scope = Scope.SINGLETON)
public interface ServiceDeployer {

    <T> Exporter<T> export(Class<T> interfaceClass, T ref, URL serviceUrl, List<URL> registryUrls);

    <T> void unexport(List<Exporter<T>> exporters, List<URL> registryUrls);

    <T> ClusterSupport<T> buildClusterSupport(Class<T> interfaceClass, URL refUrl, List<URL> registryUrls);

    <T> T refer(Class<T> interfaceClass, List<Cluster<T>> clusters, String proxyType);
}
