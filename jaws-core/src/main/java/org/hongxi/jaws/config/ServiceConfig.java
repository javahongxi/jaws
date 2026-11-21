package org.hongxi.jaws.config;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.exception.JawsErrorCode;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.common.lifecycle.ShutdownHook;
import org.hongxi.jaws.filter.ProtocolFilterWrapper;
import org.hongxi.jaws.metadata.ApplicationMetadataManager;
import org.hongxi.jaws.metadata.MetadataService;
import org.hongxi.jaws.metadata.ServiceNameMapping;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider-side service configuration responsible for exposing a service
 * implementation under one or more protocols and registering it with the
 * configured registries, similar to Dubbo's service export.
 * <p>
 * {@code export()} builds the service URL, creates a {@link Provider} and
 * {@link Exporter} via the protocol SPI (wrapped with filters), while
 * {@code unexport()} performs a graceful multi-phase shutdown: unregister from
 * the registries, stop accepting requests, drain in-flight calls, then release
 * resources.
 *
 * @see ReferenceConfig
 * @see InterfaceConfig
 *
 * <p>
 * Created by shenhongxi on 2021/3/6.
 */
public class ServiceConfig<T> extends InterfaceConfig {

    @Serial
    private static final long serialVersionUID = -3342374271064293224L;

    private static final Logger log = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * Set of identities of all exported services, used to prevent duplicate exports
     */
    private static final Set<String> EXPORTED_SERVICES = ConcurrentHashMap.newKeySet();

    /**
     * Applications whose internal {@link MetadataService} has been exported,
     * so multiple ServiceConfigs of the same application share one export.
     */
    private static final Set<String> METADATA_SERVICE_EXPORTED = ConcurrentHashMap.newKeySet();

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
     * Application-level instance URLs registered by this config (registerMode=instance),
     * kept so unexport can deregister the exact previously registered URL.
     */
    private final List<URL> registeredAppUrls = new CopyOnWriteArrayList<>();

    /**
     * Shutdown hook priority of this config. Business services use 10; the internal
     * MetadataService uses 15 so it stays reachable while business services drain,
     * and still closes before registries (20).
     */
    private int shutdownHookPriority = 10;

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
            log.warn("{} has already been exported, ignoring the duplicate export request", interfaceClass.getName());
            return;
        }

        if (protocols == null || protocols.isEmpty()) {
            throw new JawsFrameworkException(String.format(
                    "No protocol configured for service [%s], " +
                    "please configure at least one ProtocolConfig.",
                    interfaceClass.getName()));
        }

        checkInterfaceAndMethods(interfaceClass, methods);

        loadRegistryUrls();

        if (registryUrls.isEmpty()) {
            log.info("No registry configured for service [{}], will export without registering.", interfaceClass.getName());
        }

        // Application-level registration keeps one instance per application, so the
        // internal MetadataService must share a single protocol/port (v1 boundary).
        if (protocols.size() > 1) {
            for (URL registryUrl : registryUrls) {
                if (isInstanceMode(registryUrl)) {
                    throw new JawsFrameworkException(String.format(
                            "registerMode=instance supports a single protocol per application, " +
                            "but service [%s] has %d protocols configured.",
                            interfaceClass.getName(), protocols.size()));
                }
            }
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

            // Phase 1: Unregister first, so consumers start refreshing their
            // address lists as early as possible. This cannot eliminate the
            // propagation delay to each consumer, which is what Phase 2 covers.
            log.info("[GracefulShutdown] Phase 1: Unregister from registry");
            unregisterApplicationInstance();
            List<URL> interfaceRegistries = registryUrlsByMode(registryUrls, false);
            for (Exporter<T> exporter : exporters) {
                unRegister(interfaceRegistries, exporter.getUrl());
            }

            // Phase 2: Stop accepting new requests. Closes the listening socket
            // and, on the drain gate inside the server, refuses work that
            // arrives on connections which are already open.
            log.info("[GracefulShutdown] Phase 2: Stop accepting new requests, exporters={}", exporters.size());
            for (Exporter<T> exporter : exporters) {
                try {
                    exporter.stopAccept();
                } catch (Exception e) {
                    log.warn("[GracefulShutdown] Failed to stopAccept for exporter: {}", exporter.getUrl(), e);
                }
            }

            // Phase 3: Wait for in-flight requests to complete. Only the streams
            // accepted before Phase 2 are in this set; new ones were refused.
            log.info("[GracefulShutdown] Phase 3: Waiting for in-flight requests to complete, timeout={}ms", gracefulTimeout);
            for (Exporter<T> exporter : exporters) {
                try {
                    exporter.drainInflightRequests(gracefulTimeout);
                } catch (Exception e) {
                    log.warn("[GracefulShutdown] Failed to drainInflightRequests for exporter: {}", exporter.getUrl(), e);
                }
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
            throw new JawsFrameworkException(String.format(
                    "Service [%s] is already exported, duplicate export is not allowed.",
                    serviceUrl.getIdentity()));
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

        // Register service to registries (skip if no registry configured).
        // Instance-mode registries get one application-level URL carrying a metadata
        // revision instead of per-interface URLs; the rest keep the classic path.
        List<URL> instanceRegistries = registryUrlsByMode(registryUrls, true);
        List<URL> interfaceRegistries = registryUrlsByMode(registryUrls, false);
        if (!instanceRegistries.isEmpty()) {
            registerApplicationInstance(instanceRegistries, serviceUrl, protocolConfig);
        }
        if (!interfaceRegistries.isEmpty()) {
            register(interfaceRegistries, serviceUrl);
        }

        exporters.add(exporter);
        exportProtocols.add(protocol);
    }

    private void register(List<URL> registryUrls, URL serviceUrl) {
        // Record startup timestamp for consumer-side warm-up calculation
        serviceUrl.addParameter(UrlParam.Cluster.TIMESTAMP.getName(), String.valueOf(System.currentTimeMillis()));
        for (URL url : registryUrls) {
            Registry registry = getRegistry(url);
            registry.register(serviceUrl);
        }
    }

    /**
     * Application-level registration (registerMode=instance): store the service URL
     * in the in-memory metadata manager, expose the internal MetadataService once
     * per application, register one instance URL whose path is the application name
     * and whose revision parameter points at the stored metadata, and publish the
     * interface-to-application mapping to the config center.
     */
    private void registerApplicationInstance(List<URL> registryUrls, URL serviceUrl, ProtocolConfig protocolConfig) {
        // Same warm-up timestamp as the interface-level path; it travels inside the
        // stored metadata so rebuilt consumer URLs keep the original startup time.
        serviceUrl.addParameter(UrlParam.Cluster.TIMESTAMP.getName(), String.valueOf(System.currentTimeMillis()));

        String application = serviceUrl.getApplication();
        String revision = ApplicationMetadataManager.addServiceUrl(application, serviceUrl);
        exportMetadataServiceOnce(application, protocolConfig);

        URL appUrl = buildAppUrl(serviceUrl, revision);
        for (URL url : registryUrls) {
            Registry registry = getRegistry(url);
            registry.register(appUrl);
        }
        registeredAppUrls.add(appUrl);

        ServiceNameMapping.setMapping(interfaceClass.getName(), application);
        log.info("Registered application instance: application={}, revision={}, interface={}",
                application, revision, interfaceClass.getName());
    }

    /**
     * Unexport counterpart of {@link #registerApplicationInstance}: remove this
     * service from the metadata manager, then either re-register the application
     * instance with the recomputed revision (other services still exported) or
     * deregister it entirely (last service gone). The mapping is left behind on
     * purpose, same boundary as Dubbo.
     */
    private void unregisterApplicationInstance() {
        if (registeredAppUrls.isEmpty()) {
            return;
        }
        URL serviceUrl = exporters.get(0).getUrl();
        String application = serviceUrl.getApplication();
        String revision = ApplicationMetadataManager.removeServiceUrl(application, serviceUrl);
        for (URL registryUrl : registryUrlsByMode(registryUrls, true)) {
            try {
                Registry registry = getRegistry(registryUrl);
                // Remove the old instance URL before landing the new one: registries
                // key instances by ip:port (or full URL equality for the local
                // registry), so the stale revision must go first.
                for (URL appUrl : registeredAppUrls) {
                    registry.unregister(appUrl);
                }
                if (revision != null) {
                    registry.register(buildAppUrl(serviceUrl, revision));
                }
            } catch (Exception e) {
                log.warn("Failed to update application instance on registry: {}", registryUrl, e);
            }
        }
        registeredAppUrls.clear();
    }

    private URL buildAppUrl(URL serviceUrl, String revision) {
        URL appUrl = serviceUrl.createCopy();
        appUrl.setPath(serviceUrl.getApplication());
        appUrl.addParameter(UrlParam.Identity.REVISION.getName(), revision);
        return appUrl;
    }

    /**
     * Export the internal MetadataService on the same protocol/port as the business
     * services, without registering it anywhere. Guarded per application; its
     * shutdown hook runs at priority 15, after business unexport (10).
     */
    private void exportMetadataServiceOnce(String application, ProtocolConfig protocolConfig) {
        if (!METADATA_SERVICE_EXPORTED.add(application)) {
            return;
        }
        ServiceConfig<MetadataService> metadataConfig = new ServiceConfig<>();
        metadataConfig.setInterface(MetadataService.class);
        metadataConfig.setRef(revision -> ApplicationMetadataManager.getMetadataInfo(application, revision));
        metadataConfig.setApplication(application);
        // Inherit service coordinates so the provider-side serviceKey matches the
        // group/version attachments consumers copy from the discovered instance URL.
        metadataConfig.setGroup(group);
        metadataConfig.setVersion(version);
        metadataConfig.setToken(token);
        metadataConfig.setProtocol(protocolConfig);
        // No registries: reachable only point-to-point via the instance address.
        metadataConfig.setRegistries(List.of());
        metadataConfig.shutdownHookPriority = 15;
        metadataConfig.export();
    }

    private boolean isInstanceMode(URL registryUrl) {
        return JawsConstants.REGISTER_MODE_INSTANCE.equals(registryUrl.getParameter("registerMode"));
    }

    private List<URL> registryUrlsByMode(List<URL> urls, boolean instanceMode) {
        List<URL> result = new ArrayList<>();
        for (URL url : urls) {
            if (isInstanceMode(url) == instanceMode) {
                result.add(url);
            }
        }
        return result;
    }

    private Registry getRegistry(URL url) {
        RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
        if (registryFactory == null) {
            throw new JawsFrameworkException(String.format(
                    "No RegistryFactory found for registry protocol [%s], " +
                    "please make sure the corresponding registry module is in the classpath.",
                    url.getProtocol()),
                    JawsErrorCode.FRAMEWORK_REGISTER);
        }
        return registryFactory.getRegistry(url);
    }

    private void unRegister(Collection<URL> registryUrls, URL serviceUrl) {
        for (URL url : registryUrls) {
            try {
                RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
                Registry registry = registryFactory.getRegistry(url);
                registry.unregister(serviceUrl);
            } catch (Exception e) {
                log.warn("Failed to unregister service url: {}", url, e);
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
        // Register JVM shutdown hook to trigger graceful shutdown.
        // Priority 10: service unexport must run BEFORE registry close (default 20),
        // so that deregister requests are sent while the naming client is still alive.
        ShutdownHook.registerShutdownHook(this::unexport, shutdownHookPriority);
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
            throw new JawsFrameworkException("The interface class " + interfaceClass + " is not an interface!");
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