package org.hongxi.jaws.config.deploy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.support.ClusterSupport;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.exception.JawsErrorMsg;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.protocol.ProtocolFilterDecorator;
import org.hongxi.jaws.proxy.ProxyFactory;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.RegistryFactory;
import org.hongxi.jaws.rpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * Default implementation of {@link ServiceDeployer} that handles service export, unexport,
 * and reference operations including registry integration and graceful shutdown.
 */
@SpiMeta(name = JawsConstants.DEFAULT_VALUE)
public class DefaultServiceDeployer implements ServiceDeployer {

    private static final Logger log = LoggerFactory.getLogger(DefaultServiceDeployer.class);

    @Override
    public <T> Exporter<T> export(Class<T> interfaceClass, T ref, URL serviceUrl, List<URL> registryUrls) {
        // export service
        String protocolName = serviceUrl.getParameter(URLParamType.protocol);
        Protocol delegate = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(protocolName);
        Protocol protocol = new ProtocolFilterDecorator(delegate);
        Provider<T> provider = new DefaultProvider<>(interfaceClass, serviceUrl, ref);
        Exporter<T> exporter = protocol.export(provider);

        // register service
        register(registryUrls, serviceUrl);

        return exporter;
    }

    @Override
    public <T> void unexport(List<Exporter<T>> exporters, List<URL> registryUrls) {
        if (exporters == null || exporters.isEmpty()) {
            return;
        }

        // Determine graceful shutdown timeout from the first exporter's URL config
        long gracefulTimeout = exporters.get(0).getUrl().getParameter(
                URLParamType.gracefulShutdownTimeout.getName(),
                URLParamType.gracefulShutdownTimeout.intValue());

        // Phase 1: Stop accepting new requests
        log.info("[GracefulShutdown] Phase 1: Stop accepting new requests, exporters={}", exporters.size());
        for (Exporter<T> exporter : exporters) {
            try {
                exporter.stopAccept();
            } catch (Exception e) {
                log.warn("[GracefulShutdown] Failed to stopAccept for exporter: {}", exporter.getUrl(), e);
            }
        }

        // Phase 2: Wait for in-flight requests to complete
        log.info("[GracefulShutdown] Phase 2: Waiting for in-flight requests to complete, timeout={}ms", gracefulTimeout);
        for (Exporter<T> exporter : exporters) {
            try {
                exporter.awaitInactiveRequests(gracefulTimeout);
            } catch (Exception e) {
                log.warn("[GracefulShutdown] Failed to awaitInactiveRequests for exporter: {}", exporter.getUrl(), e);
            }
        }

        // Phase 3: Unregister from registry
        log.info("[GracefulShutdown] Phase 3: Unregister from registry");
        for (Exporter<T> exporter : exporters) {
            try {
                unRegister(registryUrls, exporter.getUrl());
            } catch (Exception e) {
                log.warn("[GracefulShutdown] Failed to unregister: {}", exporter.getUrl(), e);
            }
        }

        // Phase 4: Close connections and release resources
        log.info("[GracefulShutdown] Phase 4: Close connections and release resources");
        for (Exporter<T> exporter : exporters) {
            try {
                exporter.unexport();
            } catch (Exception e) {
                log.warn("[GracefulShutdown] Failed to unexport: {}", exporter.getUrl(), e);
            }
        }

        log.info("[GracefulShutdown] Graceful shutdown completed");
    }

    @Override
    public <T> ClusterSupport<T> buildClusterSupport(Class<T> interfaceClass, URL refUrl, List<URL> registryUrls) {
        ClusterSupport<T> clusterSupport = new ClusterSupport<>(interfaceClass, refUrl, registryUrls);
        clusterSupport.init();
        return clusterSupport;
    }

    @Override
    public <T> T refer(Class<T> interfaceClass, List<Cluster<T>> clusters, String proxyType) {
        ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getExtension(proxyType);
        return proxyFactory.getProxy(interfaceClass, clusters);
    }

    private void register(List<URL> registryUrls, URL serviceUrl) {
        // record startup timestamp for consumer-side warm-up calculation
        serviceUrl.addParameter(URLParamType.timestamp.getName(), String.valueOf(System.currentTimeMillis()));
        for (URL url : registryUrls) {
            // Depending on the check parameter, register failure may throw an exception that the caller should be aware of
            RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
            if (registryFactory == null) {
                throw new JawsFrameworkException(new JawsErrorMsg(500, JawsErrorMsgConstants.FRAMEWORK_REGISTER_ERROR_CODE,
                        "register error! Could not find extension for registry protocol:" + url.getProtocol()
                                + ", make sure registry module for " + url.getProtocol() + " is in classpath!"));
            }
            Registry registry = registryFactory.getRegistry(url);
            registry.register(serviceUrl);
        }
    }

    private void unRegister(Collection<URL> registryUrls, URL serviceUrl) {
        for (URL url : registryUrls) {
            // Regardless of the check setting, always attempt to unregister all and clean up properly
            try {
                RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
                Registry registry = registryFactory.getRegistry(url);
                registry.unregister(serviceUrl);
            } catch (Exception e) {
                log.warn("unregister url false: {}", url, e);
            }
        }
    }
}
