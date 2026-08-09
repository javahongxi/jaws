package org.hongxi.jaws.config;

import org.apache.commons.lang3.StringUtils;
import java.io.Serial;

import org.hongxi.jaws.common.util.MathUtils;
import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.config.annotation.ConfigDesc;
import org.hongxi.jaws.config.deploy.ServiceDeployer;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * 一个service可以按多个protocol提供服务，不同protocol使用不同port 利用export来设置protocol和port，格式如下：
     * protocol1:port1,protocol2:port2
     **/
    protected String export;

    private final AtomicBoolean exported = new AtomicBoolean(false);

    // service 对应的exporters，用于管理service服务的生命周期
    private final List<Exporter<T>> exporters = new CopyOnWriteArrayList<>();

    /**
     * 一般不用设置，由服务自己获取，但如果有多个ip，而只想用指定ip，则可以在此处指定
     */
    protected String host;

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

        Map<String, Integer> protocolPorts = getProtocolAndPort();
        for (ProtocolConfig protocolConfig : protocols) {
            Integer port = protocolPorts.get(protocolConfig.getId());
            if (port == null) {
                throw new JawsServiceException(String.format("Unknown port in service:%s, protocol:%s",
                        interfaceClass.getName(),
                        protocolConfig.getId()));
            }
            doExport(protocolConfig, port);
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

    private Map<String, Integer> getProtocolAndPort() {
        if (StringUtils.isBlank(export)) {
            throw new JawsServiceException("export should not empty in service config:" + interfaceClass.getName());
        }

        Map<String, Integer> pps = new HashMap<>();
        String[] protocolAndPorts = JawsConstants.COMMA_SPLIT_PATTERN.split(export);
        for (String pp : protocolAndPorts) {
            if (StringUtils.isBlank(pp)) {
                continue;
            }
            String[] ppDetail = pp.split(":");
            if (ppDetail.length == 2) {
                pps.put(ppDetail[0], Integer.parseInt(ppDetail[1]));
            } else if (ppDetail.length == 1) {
                if (JawsConstants.PROTOCOL_INJVM.equals(ppDetail[0])) {
                    pps.put(ppDetail[0], JawsConstants.DEFAULT_INT_VALUE);
                } else {
                    int port = MathUtils.parseInt(ppDetail[0], 0);
                    if (port < -1) {
                        throw new JawsServiceException("Export is malformed :" + export);
                    } else {
                        pps.put(JawsConstants.PROTOCOL_JAWS, port);
                    }
                }
            } else {
                throw new JawsServiceException("Export is malformed :" + export);
            }
        }
        return pps;
    }

    private void doExport(ProtocolConfig protocolConfig, int port) {
        String protocolName = protocolConfig.getName();
        if (protocolName == null || protocolName.isEmpty()) {
            protocolName = URLParamType.protocol.value();
        }

        if (port == -1) {
            if (Boolean.FALSE.equals(shareChannel)) {
                throw new JawsServiceException(
                        "Dynamic port (-1) requires shareChannel=true, please set explicit port for shareChannel=false");
            }
            port = resolveDynamicPort();
        }

        String hostAddress = host;
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
        if (JawsConstants.PROTOCOL_INJVM.equals(protocolConfig.getId())) {
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

    public String getExport() {
        return export;
    }

    public void setExport(String export) {
        this.export = export;
    }

    public AtomicBoolean getExported() {
        return exported;
    }

    public List<Exporter<T>> getExporters() {
        return Collections.unmodifiableList(exporters);
    }

    @ConfigDesc(excluded = true)
    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}