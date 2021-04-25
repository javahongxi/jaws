package org.hongxi.jaws.config;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.support.ClusterSupport;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.common.util.StringTools;
import org.hongxi.jaws.config.annotation.ConfigDesc;
import org.hongxi.jaws.config.handler.ConfigHandler;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.URL;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class RefererConfig<T> extends AbstractRefererConfig {

    private static final long serialVersionUID = -2299754608229467887L;

    private Class<T> interfaceClass;

    private String serviceInterface;

    public String getServiceInterface() {
        return serviceInterface;
    }

    public void setServiceInterface(String serviceInterface) {
        this.serviceInterface = serviceInterface;
    }

    // 具体到方法的配置
    protected List<MethodConfig> methods;

    // 点对点直连服务提供地址
    private String directUrl;

    private AtomicBoolean initialized = new AtomicBoolean(false);

    private T ref;

    private BasicRefererInterfaceConfig basicReferer;

    private List<ClusterSupport<T>> clusterSupports;

    public List<MethodConfig> getMethods() {
        return methods;
    }

    public void setMethods(List<MethodConfig> methods) {
        this.methods = methods;
    }

    public void setMethods(MethodConfig methods) {
        this.methods = Collections.singletonList(methods);
    }

    public boolean hasMethods() {
        return this.methods != null && !this.methods.isEmpty();
    }

    public T getRef() {
        if (ref == null) {
            initRef();
        }
        return ref;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized void initRef() {
        if (initialized.get()) {
            return;
        }

        try {
            interfaceClass = (Class) Class.forName(interfaceClass.getName(), true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new JawsFrameworkException("RefererConfig initRef Error: Class not found " + interfaceClass.getName(), e,
                    JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
        }

        if (CollectionUtils.isEmpty(protocols)) {
            throw new JawsFrameworkException(String.format("%s RefererConfig is malformed, for protocol not set correctly!",
                    interfaceClass.getName()));
        }

        checkInterfaceAndMethods(interfaceClass, methods);

        clusterSupports = new ArrayList<>(protocols.size());
        List<Cluster<T>> clusters = new ArrayList<>(protocols.size());
        String proxy = null;

        ConfigHandler configHandler = ExtensionLoader.getExtensionLoader(ConfigHandler.class).getExtension(JawsConstants.DEFAULT_VALUE);

        loadRegistryUrls();
        String localIp = getLocalHostAddress();
        for (ProtocolConfig protocol : protocols) {
            Map<String, String> params = new HashMap<>();
            params.put(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_REFERER);
            params.put(URLParamType.version.getName(), URLParamType.version.value());
            params.put(URLParamType.refreshTimestamp.getName(), String.valueOf(System.currentTimeMillis()));

            collectConfigParams(params, protocol, basicReferer, this);
            collectMethodConfigParams(params, this.getMethods());

            String path = StringUtils.isBlank(serviceInterface) ? interfaceClass.getName() : serviceInterface;
            URL refUrl = new URL(protocol.getName(), localIp, JawsConstants.DEFAULT_INT_VALUE, path, params);
            ClusterSupport<T> clusterSupport = createClusterSupport(refUrl, configHandler);

            clusterSupports.add(clusterSupport);
            clusters.add(clusterSupport.getCluster());

            if (proxy == null) {
                String defaultValue = StringUtils.isBlank(serviceInterface) ? URLParamType.proxy.value() : JawsConstants.PROXY_COMMON;
                proxy = refUrl.getParameter(URLParamType.proxy.getName(), defaultValue);
            }
        }

        ref = configHandler.refer(interfaceClass, clusters, proxy);

        initialized.set(true);
    }

    private ClusterSupport<T> createClusterSupport(URL refUrl, ConfigHandler configHandler) {
        List<URL> regUrls = new ArrayList<>();

        // 如果用户指定directUrls 或者 injvm协议访问，则使用local registry
        if (StringUtils.isNotBlank(directUrl) || JawsConstants.PROTOCOL_INJVM.equals(refUrl.getProtocol())) {
            URL regUrl =
                    new URL(JawsConstants.REGISTRY_PROTOCOL_LOCAL, NetUtils.LOCALHOST, JawsConstants.DEFAULT_INT_VALUE,
                            RegistryService.class.getName());
            if (StringUtils.isNotBlank(directUrl)) {
                StringBuilder duBuf = new StringBuilder(128);
                String[] dus = JawsConstants.COMMA_SPLIT_PATTERN.split(directUrl);
                for (String du : dus) {
                    if (du.contains(":")) {
                        String[] hostPort = du.split(":");
                        URL durl = refUrl.createCopy();
                        durl.setHost(hostPort[0].trim());
                        durl.setPort(Integer.parseInt(hostPort[1].trim()));
                        durl.addParameter(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);
                        duBuf.append(StringTools.urlEncode(durl.toFullStr())).append(JawsConstants.COMMA_SEPARATOR);
                    }
                }
                if (duBuf.length() > 0) {
                    duBuf.deleteCharAt(duBuf.length() - 1);
                    regUrl.addParameter(URLParamType.directUrl.getName(), duBuf.toString());
                }
            }
            regUrls.add(regUrl);
        } else { // 通过注册中心配置拼装URL，注册中心可能在本地，也可能在远端
            if (registryUrls == null || registryUrls.isEmpty()) {
                throw new IllegalStateException(
                        String.format(
                                "No registry to reference %s on the consumer %s , please config <jaws:registry address=\"...\" /> in your spring config.",
                                interfaceClass, NetUtils.LOCALHOST));
            }
            for (URL url : registryUrls) {
                regUrls.add(url.createCopy());
            }
        }

        return configHandler.buildClusterSupport(interfaceClass, regUrls, refUrl);
    }

    public synchronized void destroy() {
        if (clusterSupports != null) {
            for (ClusterSupport<T> clusterSupport : clusterSupports) {
                clusterSupport.destroy();
            }
        }
        ref = null;
        initialized.set(false);
    }

    public void setInterface(Class<T> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
    }

    public Class<?> getInterface() {
        return interfaceClass;
    }

    public String getDirectUrl() {
        return directUrl;
    }

    public void setDirectUrl(String directUrl) {
        this.directUrl = directUrl;
    }

    @ConfigDesc(excluded = true)
    public BasicRefererInterfaceConfig getBasicReferer() {
        return basicReferer;
    }

    public void setBasicReferer(BasicRefererInterfaceConfig basicReferer) {
        this.basicReferer = basicReferer;
    }

    public List<ClusterSupport<T>> getClusterSupports() {
        return clusterSupports;
    }

    public AtomicBoolean getInitialized() {
        return initialized;
    }

}