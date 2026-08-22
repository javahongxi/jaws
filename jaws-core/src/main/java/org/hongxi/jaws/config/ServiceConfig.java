package org.hongxi.jaws.config;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.hongxi.jaws.filter.ProtocolFilterWrapper;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.RegistryFactory;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.DefaultProvider;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Protocol;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serial;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider-side service configuration responsible for exposing a service
 * implementation under one or more protocols and registering it with the
 * configured registries, similar to Dubbo's service export.
 * <p>
 * {@code export()} builds the service URL, creates a {@link Provider} and
 * {@link Exporter} via the protocol SPI (wrapped with filters), while
 * {@code unexport()} performs a graceful multi-phase shutdown: stop accepting
 * requests, drain in-flight calls, unregister from registries, then release
 * resources.
 *
 * @see ReferenceConfig
 * @see AbstractInterfaceConfig
 *
 * <p>
 * Created by shenhongxi on 2021/3/6.
 */
public class ServiceConfig<T> extends AbstractInterfaceConfig {

    @Serial
    private static final long serialVersionUID = -3342374271064293224L;

    private static final Logger log = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * Set of identities of all exported services, used to prevent duplicate exports
     */
    private static final ConcurrentHashSet<String> EXPORTED_SERVICES = new ConcurrentHashSet<>();

    /**
     * Dynamic port counter for shared-channel mode with no explicit port configured
     */
    private static int dynamicPort = -1;

    /**
     * The service interface class
     */
    private Class<T> interfaceClass;

    /**
     * Reference to the interface implementation
     */
    private T ref;

    /**
     * Whether this service has been exported
     */
    private final AtomicBoolean exported = new AtomicBoolean(false);

    /**
     * Exporters for this service, used to manage the service lifecycle
     */
    private final List<Exporter<T>> exporters = new CopyOnWriteArrayList<>();

    /**
     * Protocols corresponding to each exporter, used for unexport by URL
     */
    private final List<Protocol> exportProtocols = new CopyOnWriteArrayList<>();

    /**
     * Service auth token, empty means no auth
     */
    private String token;

    /**
     * Service tag for gray release / tag-based routing.
     * Providers with a tag are only reachable by consumers that request the same tag.
     */
    private String tag;

    @Override
    protected void collectParams(Map<String, String> params) {
        super.collectParams(params);
        putIfPresent(params, "token", token);
        putIfPresent(params, "tag", tag);
    }

    public synchronized void export() {
        if (exported.get()) {
            log.warn("{} has already been exported, so ignore the export request!", interfaceClass.getName());
            return;
        }

        checkInterfaceAndMethods(interfaceClass, methods);

        loadRegistryUrls();
        if (registryUrls == null || registryUrls.isEmpty()) {
            log.info("No registry configured for service [{}], will export without registering.", interfaceClass.getName());
        }

        for (ProtocolConfig protocolConfig : protocols) {
            doExport(protocolConfig);
        }

        afterExport();
    }

    public synchronized void unexport() {
        if (!exported.get()) {
            return;
        }
        if (exporters.isEmpty()) {
            return;
        }

        try {
            // Determine graceful shutdown timeout from the first exporter's URL config
            long gracefulTimeout = exporters.get(0).getUrl().getIntParameter(UrlParam.Server.GRACEFUL_SHUTDOWN_TIMEOUT);

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
                unRegister(registryUrls, exporter.getUrl());
            }

            // Phase 4: Close connections and release resources
            log.info("[GracefulShutdown] Phase 4: Close connections and release resources");
            for (int i = 0; i < exporters.size(); i++) {
                try {
                    exportProtocols.get(i).unexport(exporters.get(i).getUrl());
                } catch (Exception e) {
                    log.warn("[GracefulShutdown] Failed to unexport: {}", exporters.get(i).getUrl(), e);
                }
            }

            log.info("[GracefulShutdown] Graceful shutdown completed");
        } finally {
            afterUnexport();
        }
    }

    private void doExport(ProtocolConfig protocolConfig) {
        String protocolName = protocolConfig.getName();
        if (protocolName == null || protocolName.isEmpty()) {
            protocolName = UrlParam.Transport.PROTOCOL.value();
        }

        int port = resolvePort(protocolConfig, protocolName);

        String hostAddress = protocolConfig.getHost();
        if (NetUtils.isInvalidLocalHost(hostAddress)) {
            hostAddress = getLocalHostAddress();
        }

        Map<String, String> map = new HashMap<>();

        map.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_SERVICE);

        collectConfigParams(map, protocolConfig, this);
        collectMethodConfigParams(map, this.getMethods());

        URL serviceUrl = new URL(protocolName, hostAddress, port, interfaceClass.getName(), map);

        if (EXPORTED_SERVICES.contains(serviceUrl.getIdentity())) {
            log.warn("{} configService is malformed, for same service ({}) already exists ",
                    interfaceClass.getName(), serviceUrl.getIdentity());
            throw new JawsFrameworkException(String.format("%s configService is malformed, for same service (%s) already exists ",
                    interfaceClass.getName(), serviceUrl.getIdentity()));
        }

        List<URL> registryUrls = new ArrayList<>();

        // injvm protocol only supports local registration; other protocols can register to local or remote
        if (JawsConstants.PROTOCOL_INJVM.equals(protocolName)) {
            URL localRegistryUrl = null;
            for (URL ru : this.registryUrls) {
                if (JawsConstants.REGISTRY_PROTOCOL_LOCAL.equals(ru.getProtocol())) {
                    localRegistryUrl = ru.createCopy();
                    break;
                }
            }
            if (localRegistryUrl == null) {
                localRegistryUrl =
                        new URL(JawsConstants.REGISTRY_PROTOCOL_LOCAL, hostAddress, 0,
                                RegistryService.class.getName());
            }

            registryUrls.add(localRegistryUrl);
        } else {
            for (URL ru : this.registryUrls) {
                registryUrls.add(ru.createCopy());
            }
        }

        // Export service via protocol
        Protocol delegate = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(protocolName);
        Protocol protocol = new ProtocolFilterWrapper(delegate);
        Provider<T> provider = new DefaultProvider<>(interfaceClass, serviceUrl, ref);
        Exporter<T> exporter = protocol.export(provider);

        // Register service to registries (skip if no registry configured)
        if (!registryUrls.isEmpty()) {
            register(registryUrls, serviceUrl);
        }

        exporters.add(exporter);
        exportProtocols.add(protocol);
    }

    private void register(List<URL> registryUrls, URL serviceUrl) {
        // Record startup timestamp for consumer-side warm-up calculation
        serviceUrl.addParameter(UrlParam.Cluster.TIMESTAMP.getName(), String.valueOf(System.currentTimeMillis()));
        for (URL url : registryUrls) {
            RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
            if (registryFactory == null) {
                throw new JawsFrameworkException("register error! Could not find extension for registry protocol:" + url.getProtocol()
                                + ", make sure registry module for " + url.getProtocol() + " is in classpath!",
                        JawsErrorCode.FRAMEWORK_REGISTER);
            }
            Registry registry = registryFactory.getRegistry(url);
            registry.register(serviceUrl);
        }
    }

    private void unRegister(Collection<URL> registryUrls, URL serviceUrl) {
        for (URL url : registryUrls) {
            try {
                RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
                Registry registry = registryFactory.getRegistry(url);
                registry.unregister(serviceUrl);
            } catch (Exception e) {
                log.warn("unregister url false: {}", url, e);
            }
        }
    }

    private int resolvePort(ProtocolConfig protocolConfig, String protocolName) {
        Integer port = protocolConfig.getPort();
        if (JawsConstants.PROTOCOL_INJVM.equals(protocolName)) {
            return 0;
        }
        if (port == null || port == -1) {
            return resolveDynamicPort();
        }
        return port;
    }

    private static synchronized int resolveDynamicPort() {
        if (dynamicPort != -1) {
            return dynamicPort;
        }
        int port = 10000;
        while (!NetUtils.isPortAvailable(port)) {
            port++;
        }
        dynamicPort = port;
        return port;
    }

    private void afterExport() {
        exported.set(true);
        for (Exporter<T> ep : exporters) {
            EXPORTED_SERVICES.add(ep.getProvider().getUrl().getIdentity());
        }
        // Register JVM shutdown hook to trigger graceful shutdown
        ShutdownHook.registerShutdownHook(this::unexport);
    }

    private void afterUnexport() {
        exported.set(false);
        for (Exporter<T> ep : exporters) {
            EXPORTED_SERVICES.remove(ep.getProvider().getUrl().getIdentity());
        }
        exporters.clear();
        exportProtocols.clear();
    }

    public Class<?> getInterface() {
        return interfaceClass;
    }

    public void setInterface(Class<T> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    public AtomicBoolean getExported() {
        return exported;
    }

    public List<Exporter<T>> getExporters() {
        return Collections.unmodifiableList(exporters);
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }
}