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
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.proxy.ProxyFactory;
import org.hongxi.jaws.registry.RegistryService;
import org.hongxi.jaws.rpc.GenericService;
import org.hongxi.jaws.rpc.URL;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    /**
     * ClusterSupport instances for each protocol, responsible for registry subscription,
     * provider discovery and Reference lifecycle management. One ClusterSupport per protocol.
     */
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

    public T getRef() {
        if (ref == null) {
            initRef();
        }
        return ref;
    }

    private synchronized void initRef() {
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
            throw new JawsFrameworkException(interfaceClass.getName() +
                    " ReferenceConfig is malformed, for protocol not set correctly!");
        }

        checkInterfaceAndMethods(interfaceClass, methods);

        loadRegistryUrls();

        String localIp = getLocalHostAddress();
        String path = StringUtils.isBlank(serviceInterface) ? interfaceClass.getName() : serviceInterface;

        clusterSupports = new ArrayList<>(protocols.size());
        List<Cluster<T>> clusters = new ArrayList<>(protocols.size());

        for (ProtocolConfig protocol : protocols) {
            URL refUrl = buildRefUrl(protocol, localIp, path);
            List<URL> regUrls = resolveRegistryUrls(refUrl);
            ClusterSupport<T> clusterSupport = new ClusterSupport<>(interfaceClass, refUrl, regUrls);
            clusterSupport.init();
            clusterSupports.add(clusterSupport);
            clusters.add(clusterSupport.getCluster());
        }

        String proxyType = generic ? "generic" : URLParamType.proxy.value();
        ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getExtension(proxyType);
        ref = proxyFactory.getProxy(interfaceClass, clusters);

        initialized.set(true);
    }

    /**
     * Build the reference URL for a given protocol.
     * <p>
     * A reference URL is a local URL that describes the consumer (reference) side rather than pointing to a remote address.
     * It carries the consumer's local IP, protocol, interface path and invocation preferences
     * (e.g. timeout, retries, load-balancing strategy). The reference URL is passed to
     * {@link ClusterSupport} so that the cluster layer is aware of the consumer's identity
     * and configuration, and it is also registered with the registry to let providers
     * observe consumer information for monitoring and routing purposes.
     */
    private URL buildRefUrl(ProtocolConfig protocol, String localIp, String path) {
        Map<String, String> params = new HashMap<>();
        params.put(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_REFERENCE);
        params.put(URLParamType.version.getName(), URLParamType.version.value());
        collectConfigParams(params, protocol, this);
        collectMethodConfigParams(params, this.getMethods());
        return new URL(protocol.getName(), localIp, 0, path, params);
    }

    /**
     * Resolve registry URLs for the given reference URL.
     * <p>
     * For directUrl or injvm protocol, a local registry is returned with direct addresses embedded;
     * otherwise, copies of the configured registry URLs are returned.
     */
    private List<URL> resolveRegistryUrls(URL refUrl) {
        if (StringUtils.isNotBlank(directUrl) || JawsConstants.PROTOCOL_INJVM.equals(refUrl.getProtocol())) {
            return buildLocalRegistryUrls(refUrl);
        }

        if (registryUrls.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "No registry found for service [%s] with protocol [%s], " +
                    "please configure at least one registry address via RegistryConfig.",
                    interfaceClass.getName(), refUrl.getProtocol()));
        }

        return registryUrls.stream().map(URL::createCopy).toList();
    }

    /**
     * Build a local registry URL with direct addresses embedded for peer-to-peer or injvm invocation.
     */
    private List<URL> buildLocalRegistryUrls(URL refUrl) {
        URL regUrl = new URL(
                JawsConstants.REGISTRY_PROTOCOL_LOCAL,
                NetUtils.LOCALHOST,
                0,
                RegistryService.class.getName()
        );
        if (StringUtils.isNotBlank(directUrl)) {
            String encodedDirectUrls = Arrays.stream(JawsConstants.COMMA_SPLIT_PATTERN.split(directUrl))
                    .filter(part -> part.contains(":"))
                    .map(part -> {
                        String[] hostPort = part.split(":");
                        URL directCopy = refUrl.createCopy();
                        directCopy.setHost(hostPort[0].trim());
                        directCopy.setPort(Integer.parseInt(hostPort[1].trim()));
                        directCopy.addParameter(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);
                        return StringTools.urlEncode(directCopy.toFullStr());
                    })
                    .collect(Collectors.joining(JawsConstants.COMMA_SEPARATOR));
            if (!encodedDirectUrls.isEmpty()) {
                regUrl.addParameter(URLParamType.directUrl.getName(), encodedDirectUrls);
            }
        }
        return List.of(regUrl);
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
}