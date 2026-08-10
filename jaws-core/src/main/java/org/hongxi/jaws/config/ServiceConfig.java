package org.hongxi.jaws.config;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.config.deploy.ServiceDeployer;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serial;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by shenhongxi on 2021/3/6.
 */
public class ServiceConfig<T> extends AbstractInterfaceConfig {

    @Serial
    private static final long serialVersionUID = -3342374271064293224L;

    private static final Logger log = LoggerFactory.getLogger(ServiceConfig.class);

    private static final ConcurrentHashSet<String> EXPORTED_SERVICES = new ConcurrentHashSet<>();

    private static int dynamicPort = -1;

    private Class<T> interfaceClass;

    // 接口实现类引用
    private T ref;

    private final AtomicBoolean exported = new AtomicBoolean(false);

    // service 对应的exporters，用于管理service服务的生命周期
    private final List<Exporter<T>> exporters = new CopyOnWriteArrayList<>();

    // service auth token, empty means no auth
    private String token;

    public synchronized void export() {
        if (exported.get()) {
            log.warn("{} has already been exported, so ignore the export request!", interfaceClass.getName());
            return;
        }

        checkInterfaceAndMethods(interfaceClass, methods);

        loadRegistryUrls();
        if (registryUrls == null || registryUrls.isEmpty()) {
            throw new IllegalStateException("Should set registry config for service:" + interfaceClass.getName());
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
        try {
            ServiceDeployer serviceDeployer =
                    ExtensionLoader.getExtensionLoader(ServiceDeployer.class).getExtension(JawsConstants.DEFAULT_VALUE);
            serviceDeployer.unexport(exporters, registryUrls);
        } finally {
            afterUnexport();
        }
    }

    private void doExport(ProtocolConfig protocolConfig) {
        String protocolName = protocolConfig.getName();
        if (protocolName == null || protocolName.isEmpty()) {
            protocolName = URLParamType.protocol.value();
        }

        int port = resolvePort(protocolConfig, protocolName);

        String hostAddress = protocolConfig.getHost();
        if (NetUtils.isInvalidLocalHost(hostAddress)) {
            hostAddress = getLocalHostAddress();
        }

        Map<String, String> map = new HashMap<>();

        map.put(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);
        map.put(URLParamType.refreshTimestamp.getName(), String.valueOf(System.currentTimeMillis()));

        collectConfigParams(map, protocolConfig, this);
        collectMethodConfigParams(map, this.getMethods());

        URL serviceUrl = new URL(protocolName, hostAddress, port, interfaceClass.getName(), map);

        if (EXPORTED_SERVICES.contains(serviceUrl.getIdentity())) {
            log.warn("{} configService is malformed, for same service ({}) already exists ",
                    interfaceClass.getName(), serviceUrl.getIdentity());
            throw new JawsFrameworkException(String.format("%s configService is malformed, for same service (%s) already exists ",
                    interfaceClass.getName(), serviceUrl.getIdentity()), JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }

        List<URL> registryUrls = new ArrayList<>();

        // injvm 协议只支持注册到本地，其他协议可以注册到local、remote
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
                        new URL(JawsConstants.REGISTRY_PROTOCOL_LOCAL, hostAddress, JawsConstants.DEFAULT_INT_VALUE,
                                RegistryService.class.getName());
            }

            registryUrls.add(localRegistryUrl);
        } else {
            for (URL ru : this.registryUrls) {
                registryUrls.add(ru.createCopy());
            }
        }

        ServiceDeployer serviceDeployer = ExtensionLoader.getExtensionLoader(ServiceDeployer.class).getExtension(JawsConstants.DEFAULT_VALUE);

        exporters.add(serviceDeployer.export(interfaceClass, ref, registryUrls, serviceUrl));
    }

    private int resolvePort(ProtocolConfig protocolConfig, String protocolName) {
        Integer port = protocolConfig.getPort();
        if (JawsConstants.PROTOCOL_INJVM.equals(protocolName)) {
            return JawsConstants.DEFAULT_INT_VALUE;
        }
        if (port == null || port == -1) {
            if (Boolean.FALSE.equals(shareChannel)) {
                throw new JawsServiceException(
                        "Dynamic port (-1) requires shareChannel=true, please set explicit port for shareChannel=false");
            }
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
}
