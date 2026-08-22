package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link AbstractRegistry} implementation that keeps registered
 * service URLs in a local map keyed by interface and endpoint type, useful
 * for tests or single-process deployments without a real registry center.
 * <p>
 * Registering or unregistering a URL immediately notifies all subscribers
 * of the matching service key via their {@link NotifyListener}.
 *
 * @see LocalRegistryFactory
 *
 * Created by shenhongxi on 2021/4/21.
 */
public class LocalRegistry extends AbstractRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalRegistry.class);

    /**
     * Map<interface/endpointType, List<URL>>, URLs in the list are distinguished by identity/id
     */
    private final ConcurrentMap<String, List<URL>> registeredServices = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>>> subscribeListeners =
            new ConcurrentHashMap<>();

    public LocalRegistry(URL url) {
        super(url);
    }

    @Override
    public void doSubscribe(URL url, NotifyListener listener) {

        String subscribeKey = getSubscribeKey(url);
        ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> urlListeners = subscribeListeners.get(subscribeKey);
        if (urlListeners == null) {
            subscribeListeners.putIfAbsent(subscribeKey, new ConcurrentHashMap<>());
            urlListeners = subscribeListeners.get(subscribeKey);
        }

        ConcurrentHashSet<NotifyListener> listeners = urlListeners.get(url);
        if (listeners == null) {
            urlListeners.putIfAbsent(url, new ConcurrentHashSet<>());
            listeners = urlListeners.get(url);
        }

        listeners.add(listener);

        List<URL> urls = discover(url);
        if (!CollectionUtils.isEmpty(urls)) {
            listener.notify(getUrl(), urls);
        }

        log.info("LocalRegistry subscribe: url={}", url);
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        String subscribeKey = getSubscribeKey(url);
        ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> urlListeners = subscribeListeners.get(subscribeKey);
        if (urlListeners != null) {
            urlListeners.remove(url);
        }

        log.info("LocalRegistry unsubscribe: url={}", url);
    }

    @Override
    public List<URL> doDiscover(URL url) {
        return registeredServices.get(getRegistryKey(url));
    }

    @Override
    public void doRegister(URL url) {
        String registryKey = getRegistryKey(url);
        synchronized (registeredServices) {
            List<URL> urls = registeredServices.get(registryKey);

            if (urls == null) {
                registeredServices.putIfAbsent(registryKey, new ArrayList<>());
                urls = registeredServices.get(registryKey);
            }
            add(url, urls);

            log.info("LocalRegistry register: url={}", url);

            notifyListeners(url);
        }
    }

    @Override
    public void doUnregister(URL url) {
        synchronized (registeredServices) {
            List<URL> urls = registeredServices.get(getRegistryKey(url));

            if (urls == null) {
                return;
            }

            remove(url, urls);

            log.info("LocalRegistry unregister: url={}", url);
            // Notify immediately after change
            notifyListeners(url);
        }
    }

    private void remove(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        removeCachedUrlByIdentity(url, urls);
    }

    private void add(URL url, List<URL> urls) {
        removeCachedUrlByIdentity(url, urls);
        urls.add(url);
    }

    private void removeCachedUrlByIdentity(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        URL oldUrl = null;
        for (URL cachedUrl : urls) {
            if (Objects.equals(url, cachedUrl)) {
                oldUrl = cachedUrl;
                break;
            }
        }

        if (oldUrl != null) {
            urls.remove(oldUrl);
        }
    }

    private void notifyListeners(URL changedUrl) {
        List<URL> interestingUrls = discover(changedUrl);
        if (interestingUrls != null) {
            ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> urlListeners = subscribeListeners.get(getSubscribeKey(changedUrl));
            if (urlListeners == null) {
                return;
            }

            for (ConcurrentHashSet<NotifyListener> listeners : urlListeners.values()) {
                for (NotifyListener listener : listeners) {
                    try {
                        listener.notify(getUrl(), interestingUrls);
                    } catch (Exception e) {
                        log.warn("Exception when notify listener {}, changedUrl: {}", listener, changedUrl, e);
                    }
                }
            }

        }
    }

    private String getRegistryKey(URL url) {
        String keyPrefix = url.getPath();
        String endpointType = url.getParameter(UrlParam.Identity.ENDPOINT_TYPE.getName());
        if (endpointType != null) {
            return keyPrefix + JawsConstants.PATH_SEPARATOR + endpointType;
        } else {
            log.warn("Url needs an endpointType param in localRegistry, url={}", url);
            return keyPrefix;
        }
    }

    private String getSubscribeKey(URL url) {
        return getRegistryKey(url);
    }
}