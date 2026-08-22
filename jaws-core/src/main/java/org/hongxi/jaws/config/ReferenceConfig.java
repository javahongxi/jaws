package org.hongxi.jaws.config;

import org.apache.commons.lang3.StringUtils;
import java.io.Serial;
import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.ConsumerCoordinator;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.proxy.ProxyFactory;
import org.hongxi.jaws.rpc.GenericService;
import org.hongxi.jaws.rpc.URL;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumer-side reference configuration that lazily creates a proxy for a
 * remote service, similar to Dubbo's reference subscribe.
 * <p>
 * On first {@code getRef()} it builds one {@link ConsumerCoordinator} per
 * protocol (registry-based or direct-URL point-to-point), each owning a
 * {@link Cluster}, and then obtains the proxy from the {@link ProxyFactory}
 * SPI. Supports generic invocation where the interface class is replaced by
 * {@link GenericService} so consumers need no interface JAR.
 *
 * @see ServiceConfig
 * @see AbstractInterfaceConfig
 *
 * <p>
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
     * ConsumerCoordinator instances for each protocol, responsible for registry subscription,
     * provider discovery and Reference lifecycle management. One ConsumerCoordinator per protocol.
     */
    private List<ConsumerCoordinator<T>> consumerCoordinators;

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

    @Override
    protected void collectParams(Map<String, String> params) {
        super.collectParams(params);
        putIfPresent(params, "directUrl", directUrl);
    }

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
                        "Generic invocation requires serviceInterface to be set to the real interface name");
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

        consumerCoordinators = new ArrayList<>(protocols.size());
        List<Cluster<T>> clusters = new ArrayList<>(protocols.size());

        for (ProtocolConfig protocol : protocols) {
            URL refUrl = buildRefUrl(protocol, localIp, path);
            ConsumerCoordinator<T> consumerCoordinator;
            if (StringUtils.isNotBlank(directUrl)) {
                consumerCoordinator = ConsumerCoordinator.forDirectUrls(interfaceClass, refUrl, directUrl);
            } else {
                List<URL> regUrls = resolveRegistryUrls(refUrl);
                consumerCoordinator = ConsumerCoordinator.forRegistry(interfaceClass, refUrl, regUrls);
            }
            consumerCoordinator.init();
            consumerCoordinators.add(consumerCoordinator);
            clusters.add(consumerCoordinator.getCluster());
        }

        String proxyType = generic ? "generic" : UrlParam.Transport.PROXY.value();
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
     * {@link ConsumerCoordinator} so that the cluster layer is aware of the consumer's identity
     * and configuration, and it is also registered with the registry to let providers
     * observe consumer information for monitoring and routing purposes.
     */
    private URL buildRefUrl(ProtocolConfig protocol, String localIp, String path) {
        Map<String, String> params = new HashMap<>();
        params.put(UrlParam.Identity.ENDPOINT_TYPE.getName(), JawsConstants.ENDPOINT_TYPE_REFERENCE);
        params.put(UrlParam.Identity.VERSION.getName(), UrlParam.Identity.VERSION.value());
        collectConfigParams(params, protocol, this);
        collectMethodConfigParams(params, this.getMethods());
        return new URL(protocol.getName(), localIp, 0, path, params);
    }

    /**
     * Resolve registry URLs for the given reference URL.
     */
    private List<URL> resolveRegistryUrls(URL refUrl) {
        if (registryUrls.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "No registry found for service [%s] with protocol [%s], " +
                    "please configure at least one registry address via RegistryConfig.",
                    interfaceClass.getName(), refUrl.getProtocol()));
        }

        return registryUrls.stream().map(URL::createCopy).toList();
    }

    public synchronized void destroy() {
        if (consumerCoordinators != null) {
            for (ConsumerCoordinator<T> consumerCoordinator : consumerCoordinators) {
                consumerCoordinator.destroy();
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

    public List<ConsumerCoordinator<T>> getConsumerCoordinators() {
        return consumerCoordinators;
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