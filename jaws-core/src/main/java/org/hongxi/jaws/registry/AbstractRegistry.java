package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <pre>
 * Abstract registry.
 *
 * Performs createCopy on all incoming URLs to prevent objects in the registry from being modified,
 * avoiding potential concurrency issues.
 *
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/21.
 */
public abstract class AbstractRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(AbstractRegistry.class);

    protected String registryClassName = this.getClass().getSimpleName();

    private final URL registryUrl;

    protected final Set<URL> registered = ConcurrentHashMap.newKeySet();

    protected final Map<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<>();

    public AbstractRegistry(URL url) {
        this.registryUrl = url.createCopy();
    }

    @Override
    public void register(URL url) {
        log.info("[{}] Url ({}) will register to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        registered.add(url);
        doRegister(removeRegistryUnnecessaryParams(url.createCopy()));
    }

    @Override
    public void unregister(URL url) {
        log.info("[{}] Url ({}) will unregister to Registry [{}]", registryClassName, url, registryUrl.getIdentity());
        doUnregister(removeRegistryUnnecessaryParams(url.createCopy()));
        registered.remove(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        log.info("[{}] Listener ({}) will subscribe to url ({}) in Registry [{}]",
                registryClassName, listener, url, registryUrl.getIdentity());
        subscribed.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(listener);
        doSubscribe(url.createCopy(), listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        log.info("[{}] Listener ({}) will unsubscribe from url ({}) in Registry [{}]",
                registryClassName, listener, url, registryUrl.getIdentity());
        doUnsubscribe(url.createCopy(), listener);
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                subscribed.remove(url);
            }
        }
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
     *   <li>Provider-local settings (e.g. thread pool, server connections, codec, transportFactory)
     *       that are transport/server concerns and not needed by consumers.</li>
     *   <li>Consumer-local settings (e.g. retries, loadBalance, retryPolicy, check)
     *       that each consumer configures independently and should not be inherited from the provider.</li>
     * </ul>
     */
    private URL removeRegistryUnnecessaryParams(URL url) {
        // Transport SPI: codec is a local transport concern, consumer applies default on connect
        url.getParameters().remove(UrlParam.Transport.CODEC.getName());
        url.getParameters().remove(UrlParam.Transport.TRANSPORT_FACTORY.getName());

        // Provider-local server settings
        url.getParameters().remove(UrlParam.Server.MAX_CONNECTIONS.getName());
        url.getParameters().remove(UrlParam.Server.MIN_WORKER_THREADS.getName());
        url.getParameters().remove(UrlParam.Server.MAX_WORKER_THREADS.getName());
        url.getParameters().remove(UrlParam.Server.WORKER_QUEUE_SIZE.getName());
        url.getParameters().remove(UrlParam.Transport.MAX_CONTENT_LENGTH.getName());
        url.getParameters().remove(UrlParam.Server.ACCESS_LOG.getName());
        // NOTE: heartbeat is intentionally kept — it is a shared protocol-level parameter
        // that both provider and consumer need for connection keep-alive.

        // Consumer-local settings: each consumer configures these independently
        url.getParameters().remove(UrlParam.Cluster.RETRIES.getName());
        url.getParameters().remove(UrlParam.Client.CHECK.getName());
        url.getParameters().remove(UrlParam.Client.THROW_EXCEPTION.getName());
        url.getParameters().remove(UrlParam.Cluster.LOAD_BALANCE.getName());
        url.getParameters().remove(UrlParam.Cluster.RETRY_POLICY.getName());
        url.getParameters().remove(UrlParam.Transport.REQUEST_TIMEOUT.getName());
        url.getParameters().remove(UrlParam.Transport.CONNECT_TIMEOUT.getName());
        url.getParameters().remove(UrlParam.Transport.FILTER.getName());
        url.getParameters().remove(UrlParam.Client.FUSING_THRESHOLD.getName());

        return url;
    }

    public Set<URL> getRegistered() {
        return Collections.unmodifiableSet(registered);
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return Collections.unmodifiableMap(subscribed);
    }

    protected abstract void doRegister(URL url);

    protected abstract void doUnregister(URL url);

    protected abstract void doSubscribe(URL url, NotifyListener listener);

    protected abstract void doUnsubscribe(URL url, NotifyListener listener);

    protected abstract List<URL> doDiscover(URL url);
}