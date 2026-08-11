package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * Abstract registry。
 *
 * 对进出的url都进行createCopy保护，避免registry中的对象被修改，避免潜在的并发问题。
 *
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(AbstractRegistry.class);

    protected String registryClassName = this.getClass().getSimpleName();

    private final URL registryUrl;

    protected final Set<URL> registeredServiceUrls = new ConcurrentHashSet<>();

    public AbstractRegistry(URL url) {
        this.registryUrl = url.createCopy();
    }

    @Override
    public void register(URL url) {
        log.info("[{}] Url ({}) will register to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        doRegister(removeRegistryUnnecessaryParams(url.createCopy()));
        registeredServiceUrls.add(url);
    }

    @Override
    public void unregister(URL url) {
        log.info("[{}] Url ({}) will unregister to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        doUnregister(removeRegistryUnnecessaryParams(url.createCopy()));
        registeredServiceUrls.remove(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        log.info("[{}] Listener ({}) will subscribe to url ({}) in Registry [{}]",
                registryClassName, listener, url, registryUrl.getIdentity());
        doSubscribe(url.createCopy(), listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        log.info("[{}] Listener ({}) will unsubscribe from url ({}) in Registry [{}]",
                registryClassName, listener, url, registryUrl.getIdentity());
        doUnsubscribe(url.createCopy(), listener);
    }

    @Override
    public List<URL> discover(URL url) {
        List<URL> results = new ArrayList<>();
        List<URL> urls = doDiscover(url.createCopy());
        if (urls != null) {
            for (URL u : urls) {
                results.add(u.createCopy());
            }
        }
        return results;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    /**
     * Remove parameters that are irrelevant to service discovery before interacting with the registry.
     * <p>
     * These fall into two categories:
     * <ul>
     *   <li>Provider-local settings (e.g. thread pool, server connections, codec, endpointFactory)
     *       that are transport/server concerns and not needed by consumers.</li>
     *   <li>Consumer-local settings (e.g. retries, loadBalance, cluster, haStrategy, check)
     *       that each consumer configures independently and should not be inherited from the provider.</li>
     * </ul>
     */
    private URL removeRegistryUnnecessaryParams(URL url) {
        // Transport SPI: codec is a local transport concern, consumer applies default on connect
        url.getParameters().remove(URLParamType.codec.getName());
        url.getParameters().remove(URLParamType.endpointFactory.getName());

        // Provider-local server settings
        url.getParameters().remove(URLParamType.maxServerConnections.getName());
        url.getParameters().remove(URLParamType.minWorkerThreads.getName());
        url.getParameters().remove(URLParamType.maxWorkerThreads.getName());
        url.getParameters().remove(URLParamType.workerQueueSize.getName());
        url.getParameters().remove(URLParamType.maxContentLength.getName());
        url.getParameters().remove(URLParamType.shareChannel.getName());
        url.getParameters().remove(URLParamType.accessLog.getName());

        // Provider-local client connection settings
        url.getParameters().remove(URLParamType.minClientConnections.getName());
        url.getParameters().remove(URLParamType.maxClientConnections.getName());
        url.getParameters().remove(URLParamType.maxConnectionsPerGroup.getName());

        // Consumer-local settings: each consumer configures these independently
        url.getParameters().remove(URLParamType.retries.getName());
        url.getParameters().remove(URLParamType.check.getName());
        url.getParameters().remove(URLParamType.throwException.getName());
        url.getParameters().remove(URLParamType.cluster.getName());
        url.getParameters().remove(URLParamType.loadBalance.getName());
        url.getParameters().remove(URLParamType.haStrategy.getName());
        url.getParameters().remove(URLParamType.requestTimeout.getName());
        url.getParameters().remove(URLParamType.connectTimeout.getName());
        url.getParameters().remove(URLParamType.filter.getName());
        url.getParameters().remove(URLParamType.fusingThreshold.getName());

        return url;
    }

    protected abstract void doRegister(URL url);

    protected abstract void doUnregister(URL url);

    protected abstract void doSubscribe(URL url, NotifyListener listener);

    protected abstract void doUnsubscribe(URL url, NotifyListener listener);

    protected abstract List<URL> doDiscover(URL url);
}