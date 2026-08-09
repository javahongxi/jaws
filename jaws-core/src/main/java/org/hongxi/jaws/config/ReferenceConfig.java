package org.hongxi.jaws.config;

import org.apache.commons.lang3.StringUtils;
import java.io.Serial;
import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.support.ClusterSupport;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.common.util.StringTools;
import org.hongxi.jaws.config.deploy.ServiceDeployer;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.GenericService;
import org.hongxi.jaws.rpc.URL;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class ReferenceConfig<T> extends AbstractInterfaceConfig {

    @Serial
    private static final long serialVersionUID = -2299754608229467887L;

    /**
     * The interface class of the reference service.
     */
    private Class<T> interfaceClass;

    /**
     * The interface proxy reference
     */
    private T ref;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private List<ClusterSupport<T>> clusterSupports;

    /**
     * The URL for peer-to-peer invocation.
     */
    private String directUrl;

    /**
     * Whether to use generic invocation (no interface JAR dependency on consumer side)
     */
    private boolean generic;

    /**
     * The real interface name to invoke when using generic invocation
     */
    private String serviceInterface;

    protected Boolean asyncInitConnection;

    public T getRef() {
        if (ref == null) {
            initRef();
        }
        return ref;
    }

    public synchronized void initRef() {
        if (initialized.get()) {
            return;
        }

        // For generic invocation, override interfaceClass to GenericService
        if (generic) {
            if (StringUtils.isBlank(serviceInterface)) {
                throw new JawsFrameworkException(
                        "Generic invocation requires serviceInterface to be set to the real interface name",
                        JawsErrorMsgConstants.FRAMEWORK_INIT_ERROR);
            }
            // noinspection unchecked
            interfaceClass = (Class<T>) GenericService.class;
        }

        if (CollectionUtils.isEmpty(protocols)) {
            throw new JawsFrameworkException(String.format("%s ReferenceConfig is malformed, for protocol not set correctly!",
                    interfaceClass.getName()));
        }

        checkInterfaceAndMethods(interfaceClass, methods);

        clusterSupports = new ArrayList<>(protocols.size());
        List<Cluster<T>> clusters = new ArrayList<>(protocols.size());

        ServiceDeployer serviceDeployer = ExtensionLoader.getExtensionLoader(ServiceDeployer.class).getExtension(JawsConstants.DEFAULT_VALUE);

        loadRegistryUrls();
        String localIp = getLocalHostAddress();
        for (ProtocolConfig protocol : protocols) {
            Map<String, String> params = new HashMap<>();
            params.put(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_REFERENCE);
            params.put(URLParamType.version.getName(), URLParamType.version.value());
            params.put(URLParamType.refreshTimestamp.getName(), String.valueOf(System.currentTimeMillis()));

            collectConfigParams(params, protocol, this);
            collectMethodConfigParams(params, this.getMethods());

            String path = StringUtils.isBlank(serviceInterface) ? interfaceClass.getName() : serviceInterface;
            URL refUrl = new URL(protocol.getName(), localIp, JawsConstants.DEFAULT_INT_VALUE, path, params);
            ClusterSupport<T> clusterSupport = createClusterSupport(refUrl, serviceDeployer);

            clusterSupports.add(clusterSupport);
            clusters.add(clusterSupport.getCluster());
        }

        String proxyType = generic ? "generic" : URLParamType.proxy.value();
        ref = serviceDeployer.refer(interfaceClass, clusters, proxyType);

        initialized.set(true);
    }

    private ClusterSupport<T> createClusterSupport(URL refUrl, ServiceDeployer serviceDeployer) {
        List<URL> regUrls = new ArrayList<>();

        // 如果用户指定directUrls 或者 injvm协议访问，则使用local registry
        if (StringUtils.isNotBlank(directUrl) || JawsConstants.PROTOCOL_INJVM.equals(refUrl.getProtocol())) {
            URL regUrl =
                    new URL(JawsConstants.REGISTRY_PROTOCOL_LOCAL, NetUtils.LOCALHOST, JawsConstants.DEFAULT_INT_VALUE,
                            RegistryService.class.getName());
            if (StringUtils.isNotBlank(directUrl)) {
                StringBuilder urlBuilder = new StringBuilder(128);
                String[] urlParts = JawsConstants.COMMA_SPLIT_PATTERN.split(directUrl);
                for (String urlPart : urlParts) {
                    if (urlPart.contains(":")) {
                        String[] hostPort = urlPart.split(":");
                        URL directCopy = refUrl.createCopy();
                        directCopy.setHost(hostPort[0].trim());
                        directCopy.setPort(Integer.parseInt(hostPort[1].trim()));
                        directCopy.addParameter(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);
                        urlBuilder.append(StringTools.urlEncode(directCopy.toFullStr())).append(JawsConstants.COMMA_SEPARATOR);
                    }
                }
                if (urlBuilder.length() > 0) {
                    urlBuilder.deleteCharAt(urlBuilder.length() - 1);
                    regUrl.addParameter(URLParamType.directUrl.getName(), urlBuilder.toString());
                }
            }
            regUrls.add(regUrl);
        } else {
            // 通过注册中心配置拼装URL，注册中心可能在本地，也可能在远端
            if (registryUrls == null || registryUrls.isEmpty()) {
                throw new IllegalStateException(
                        String.format(
                                "No registry to reference %s on the consumer %s, please configure registry address first.",
                                interfaceClass, NetUtils.LOCALHOST));
            }
            for (URL url : registryUrls) {
                regUrls.add(url.createCopy());
            }
        }

        return serviceDeployer.buildClusterSupport(interfaceClass, regUrls, refUrl);
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

    public Class<?> getInterface() {
        return interfaceClass;
    }

    public void setInterface(Class<T> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
    }

    public String getServiceInterface() {
        return serviceInterface;
    }

    public void setServiceInterface(String serviceInterface) {
        this.serviceInterface = serviceInterface;
    }

    public String getDirectUrl() {
        return directUrl;
    }

    public void setDirectUrl(String directUrl) {
        this.directUrl = directUrl;
    }

    public List<ClusterSupport<T>> getClusterSupports() {
        return clusterSupports;
    }

    public AtomicBoolean getInitialized() {
        return initialized;
    }

    public boolean isGeneric() {
        return generic;
    }

    public void setGeneric(boolean generic) {
        this.generic = generic;
    }

    public Boolean getAsyncInitConnection() {
        return asyncInitConnection;
    }

    public void setAsyncInitConnection(Boolean asyncInitConnection) {
        this.asyncInitConnection = asyncInitConnection;
    }
}