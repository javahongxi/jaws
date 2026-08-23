package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registry base class adding fail-back semantics on top of a concrete
 * registry implementation: failed register/unregister/subscribe/unsubscribe
 * operations are tracked and periodically retried, and discovery degrades
 * to the last successful result when the registry is unreachable.
 */
public abstract class FailbackRegistry extends AbstractRegistry {

    private static final Logger log = LoggerFactory.getLogger(FailbackRegistry.class);

    private static final ScheduledExecutorService retryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jaws-registry-failback-retry");
                t.setDaemon(true);
                return t;
            });

    private final Set<URL> failedRegistered = ConcurrentHashMap.newKeySet();
    private final Set<URL> failedUnregistered = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<URL, Set<NotifyListener>> failedSubscribed = new ConcurrentHashMap<>();
    private final ConcurrentMap<URL, Set<NotifyListener>> failedUnsubscribed = new ConcurrentHashMap<>();

    /** Last successful discovery result per service URL, used as fallback when the registry is unreachable. */
    private final ConcurrentMap<URL, List<URL>> discoveryCache = new ConcurrentHashMap<>();

    public FailbackRegistry(URL url) {
        super(url);

        long retryPeriod = url.getLongParameter(UrlParam.Registry.RETRY_PERIOD);
        retryExecutor.scheduleAtFixedRate(() -> {
            try {
                retry();
            } catch (Exception e) {
                log.warn("[{}] Failed to retry in failback registry", registryClassName, e);
            }
        }, retryPeriod, retryPeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    public void register(URL url) {
        failedRegistered.remove(url);
        failedUnregistered.remove(url);

        try {
            super.register(url);
        } catch (Exception e) {
            // If the startup detection is opened, the Exception is thrown directly.
            if (shouldCheck(url)) {
                throw new JawsFrameworkException(String.format("[%s] Failed to register %s to %s",
                        registryClassName, url, getUrl()), e);
            }
            failedRegistered.add(url);
        }
    }

    @Override
    public void unregister(URL url) {
        failedRegistered.remove(url);
        failedUnregistered.remove(url);

        try {
            super.unregister(url);
        } catch (Exception e) {
            // If the startup detection is opened, the Exception is thrown directly.
            if (shouldCheck(url)) {
                throw new JawsFrameworkException(String.format("[%s] Failed to unregister %s to %s",
                        registryClassName, url, getUrl()), e);
            }
            failedUnregistered.add(url);
        }
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        removeFailedSubAndUnsub(url, listener);

        try {
            super.subscribe(url, listener);
        } catch (Exception e) {
            // If the startup detection is opened, the Exception is thrown directly.
            if (shouldCheck(url)) {
                throw new JawsFrameworkException(String.format("[%s] Failed to subscribe %s from %s",
                        registryClassName, url, getUrl()), e);
            }
            addFailedSubscribed(url, listener);
        }
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        removeFailedSubAndUnsub(url, listener);

        try {
            super.unsubscribe(url, listener);
        } catch (Exception e) {
            // If the startup detection is opened, the Exception is thrown directly.
            if (shouldCheck(url)) {
                throw new JawsFrameworkException(String.format("[%s] Failed to unsubscribe %s from %s",
                        registryClassName, url, getUrl()), e);
            }
            addFailedUnsubscribed(url, listener);
        }
    }

    @Override
    public List<URL> discover(URL url) {
        try {
            List<URL> discovered = super.discover(url);
            discoveryCache.put(url, discovered);
            return discovered;
        } catch (Exception e) {
            List<URL> cached = discoveryCache.get(url);
            if (cached != null) {
                log.warn("Failed to discover url:{} in registry ({}), falling back to {} cached urls",
                        url, getUrl(), cached.size(), e);
                return cached;
            }
            log.warn("Failed to discover url:{} in registry ({}), no cached urls available", url, getUrl(), e);
            return List.of();
        }
    }

    private boolean shouldCheck(URL url) {
        return getUrl().getBoolParameter(UrlParam.Client.CHECK)
                && url.getBoolParameter(UrlParam.Client.CHECK)
                && (url.getPort() != 0);
    }

    private void removeFailedSubAndUnsub(URL url, NotifyListener listener) {
        Set<NotifyListener> listeners = failedSubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
        listeners = failedUnsubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Recover all registered and subscribed services after registry reconnection.
     * <p>
     * Re-queues all tracked registrations and subscriptions into the failback retry mechanism,
     * so they will be retried by the periodic retry executor.
     * Subclasses should call this method in their reconnection callback.
     */
    protected void recover() {
        // Re-queue all registered URLs
        Set<URL> registered = new HashSet<>(getRegistered());
        if (!registered.isEmpty()) {
            log.info("[{}] Recover registered urls: {}", registryClassName, registered);
            failedRegistered.addAll(registered);
        }
        // Re-queue all subscribed url-listener pairs
        Map<URL, Set<NotifyListener>> subscribed = new HashMap<>(getSubscribed());
        if (!subscribed.isEmpty()) {
            log.info("[{}] Recover subscribed urls: {}", registryClassName, subscribed.keySet());
            for (Map.Entry<URL, Set<NotifyListener>> entry : subscribed.entrySet()) {
                for (NotifyListener listener : entry.getValue()) {
                    addFailedSubscribed(entry.getKey(), listener);
                }
            }
        }
    }

    private void addFailedSubscribed(URL url, NotifyListener listener) {
        failedSubscribed.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(listener);
    }

    private void addFailedUnsubscribed(URL url, NotifyListener listener) {
        failedUnsubscribed.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(listener);
    }

    // Package-private for deterministic testing besides the scheduled retry
    void retry() {
        if (!failedRegistered.isEmpty()) {
            Set<URL> failed = new HashSet<>(failedRegistered);
            log.info("[{}] Retry register {}", registryClassName, failed);
            for (URL url : failed) {
                try {
                    super.register(url);
                    failedRegistered.remove(url);
                } catch (Exception e) {
                    log.warn("[{}] Failed to retry register {}, retry later",
                            registryClassName, url, e);
                }
            }
        }

        if (!failedUnregistered.isEmpty()) {
            Set<URL> failed = new HashSet<>(failedUnregistered);
            log.info("[{}] Retry unregister {}", registryClassName, failed);
            for (URL url : failed) {
                try {
                    super.unregister(url);
                    failedUnregistered.remove(url);
                } catch (Exception e) {
                    log.warn("[{}] Failed to retry unregister {}, retry later",
                            registryClassName, url, e);
                }
            }
        }

        retrySubscriptions(failedSubscribed, true);
        retrySubscriptions(failedUnsubscribed, false);
    }

    private void retrySubscriptions(ConcurrentMap<URL, Set<NotifyListener>> failedMap,
                                    boolean subscribe) {
        // Drop entries whose listeners have all been handled
        failedMap.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
        if (failedMap.isEmpty()) {
            return;
        }

        log.info("[{}] Retry {} {}", registryClassName, subscribe ? "subscribe" : "unsubscribe", failedMap);
        for (Map.Entry<URL, Set<NotifyListener>> entry : failedMap.entrySet()) {
            URL url = entry.getKey();
            for (NotifyListener listener : entry.getValue()) {
                try {
                    if (subscribe) {
                        super.subscribe(url, listener);
                    } else {
                        super.unsubscribe(url, listener);
                    }
                    entry.getValue().remove(listener);
                } catch (Exception e) {
                    log.warn("[{}] Failed to retry {} {}, retry later",
                            registryClassName, subscribe ? "subscribe" : "unsubscribe", url, e);
                }
            }
        }
    }
}