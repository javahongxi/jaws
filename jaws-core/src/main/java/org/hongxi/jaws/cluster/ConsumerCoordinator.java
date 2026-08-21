package org.hongxi.jaws.cluster;

import org.hongxi.jaws.cluster.directory.RegistryDirectory;
import org.hongxi.jaws.cluster.directory.StaticDirectory;
import org.hongxi.jaws.cluster.loadbalance.AbstractLoadBalance;
import org.hongxi.jaws.cluster.router.DynamicConfigRouter;
import org.hongxi.jaws.cluster.router.TagRouter;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.filter.ProtocolFilterWrapper;
import org.hongxi.jaws.rpc.Protocol;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates a {@link Directory} and a {@link Cluster}: the directory discovers
 * and manages service references, while the cluster handles load balancing,
 * failover, and invocation routing.
 * <p>
 * Use the factory methods to create instances:
 * <ul>
 *   <li>{@link #forRegistry} for registry-based service discovery</li>
 *   <li>{@link #forDirectUrls} for direct peer-to-peer connections</li>
 * </ul>
 */
public class ConsumerCoordinator<T> {

    private static final Logger log = LoggerFactory.getLogger(ConsumerCoordinator.class);

    private final Class<T> interfaceClass;
    private final URL url;
    private final Directory<T> directory;

    private Cluster<T> cluster;
    private DynamicConfigRouter<T> dynamicConfigRouter;

    private ConsumerCoordinator(Class<T> interfaceClass, URL url, Directory<T> directory) {
        this.interfaceClass = interfaceClass;
        this.url = url;
        this.directory = directory;
    }

    /**
     * Create a ConsumerCoordinator backed by a {@link RegistryDirectory} for registry-based
     * service discovery.
     *
     * @param interfaceClass the service interface
     * @param refUrl         the consumer reference URL (nodeType=reference)
     * @param registryUrls   the list of registry URLs to subscribe to
     * @return a new ConsumerCoordinator instance (not yet initialized)
     */
    public static <T> ConsumerCoordinator<T> forRegistry(Class<T> interfaceClass, URL refUrl, List<URL> registryUrls) {
        Protocol protocol = new ProtocolFilterWrapper(
                ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(refUrl.getProtocol()));
        URL consumerUrl = refUrl.createCopy();
        consumerUrl.addParameter(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);
        RegistryDirectory<T> directory = new RegistryDirectory<>(interfaceClass, refUrl, consumerUrl, registryUrls, protocol);
        return new ConsumerCoordinator<>(interfaceClass, refUrl, directory);
    }

    /**
     * Create a ConsumerCoordinator backed by a {@link StaticDirectory} for direct
     * peer-to-peer connections (no registry involved).
     *
     * @param interfaceClass the service interface
     * @param refUrl         the consumer reference URL (nodeType=reference)
     * @param directUrls     comma-separated list of direct provider addresses (host:port)
     * @return a new ConsumerCoordinator instance (not yet initialized)
     */
    public static <T> ConsumerCoordinator<T> forDirectUrls(Class<T> interfaceClass, URL refUrl, String directUrls) {
        Protocol protocol = new ProtocolFilterWrapper(
                ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(refUrl.getProtocol()));
        URL consumerUrl = refUrl.createCopy();
        consumerUrl.addParameter(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);

        List<Reference<T>> references = new ArrayList<>();
        String[] splits = JawsConstants.COMMA_SPLIT_PATTERN.split(directUrls);
        for (String s : splits) {
            String[] hostPort = s.trim().split(":");
            URL serviceUrl = refUrl.createCopy();
            serviceUrl.setHost(hostPort[0].trim());
            serviceUrl.setPort(Integer.parseInt(hostPort[1].trim()));
            serviceUrl.addParameter(URLParamType.nodeType.getName(), JawsConstants.NODE_TYPE_SERVICE);
            Reference<T> reference = protocol.refer(interfaceClass, serviceUrl);
            references.add(reference);
        }

        StaticDirectory<T> directory = new StaticDirectory<>(consumerUrl, references);
        return new ConsumerCoordinator<>(interfaceClass, refUrl, directory);
    }

    /**
     * Initialize the directory, cluster, and wire them together.
     */
    public void init() {
        long start = System.currentTimeMillis();
        prepareCluster();

        // Register the change listener BEFORE directory init so that the initial
        // discovery results are propagated to the cluster via the listener callback.
        subscribeDirectory();
        directory.init();

        // For StaticDirectory (directUrl mode), references are set in the constructor
        // before the change listener is registered, so the cluster never receives
        // the initial onRefresh callback. Trigger it explicitly here.
        if (CollectionUtils.isEmpty(cluster.getReferences()) && !CollectionUtils.isEmpty(directory.getReferences())) {
            cluster.onRefresh(directory.getReferences());
        }

        cluster.init();

        if (CollectionUtils.isEmpty(cluster.getReferences())) {
            if (!url.getBoolParameter(URLParamType.check)) {
                log.warn("No services found for refer {}/{}", url.getPath(), url.getVersion());
            } else {
                throw new JawsFrameworkException(
                        String.format("ConsumerCoordinator No service urls for the refer:%s", url.getIdentity()),
                        JawsErrorMsgConstants.SERVICE_NOT_FOUND);
            }
        }

        log.info("Cluster init cost {}ms, refer size={}, cluster={}",
                System.currentTimeMillis() - start,
                cluster.getReferences().size(),
                cluster.getUrl().toSimpleString());
    }

    public void destroy() {
        if (directory != null) {
            directory.destroy();
        }
        if (cluster != null) {
            cluster.destroy();
        }
        if (dynamicConfigRouter != null) {
            dynamicConfigRouter.destroy();
        }
    }

    private void prepareCluster() {
        String clusterName = url.getParameter(URLParamType.cluster);
        String loadBalanceName = url.getParameter(URLParamType.loadBalance);
        String haStrategyName = url.getParameter(URLParamType.haStrategy);

        // noinspection unchecked
        cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getExtension(clusterName);
        // noinspection unchecked
        LoadBalance<T> loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(loadBalanceName);
        // noinspection unchecked
        HaStrategy<T> ha = ExtensionLoader.getExtensionLoader(HaStrategy.class).getExtension(haStrategyName);
        ha.setUrl(url);
        cluster.setLoadBalance(loadBalance);
        cluster.setHaStrategy(ha);
        cluster.setUrl(url);

        // Register routers for traffic control
        if (loadBalance instanceof AbstractLoadBalance) {
            // TagRouter: filters providers by tag for gray release
            TagRouter<T> tagRouter = new TagRouter<>();
            ((AbstractLoadBalance<T>) loadBalance).addRouter(tagRouter);

            // DynamicConfigRouter: dynamic routing rules from configuration center
            dynamicConfigRouter = new DynamicConfigRouter<>(interfaceClass.getName());
            ((AbstractLoadBalance<T>) loadBalance).addRouter(dynamicConfigRouter);
        }
    }

    /**
     * Register a change listener on the directory so that reference updates
     * are propagated to the cluster.
     */
    private void subscribeDirectory() {
        directory.addChangeListener(references -> cluster.onRefresh(references));
    }

    public Cluster<T> getCluster() {
        return cluster;
    }

    public URL getUrl() {
        return url;
    }
}