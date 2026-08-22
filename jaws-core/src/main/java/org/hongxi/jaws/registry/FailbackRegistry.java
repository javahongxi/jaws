package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Failback registry
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
public abstract class FailbackRegistry extends AbstractRegistry {

    private static final Logger log = LoggerFactory.getLogger(FailbackRegistry.class);

    private static final ScheduledExecutorService retryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jaws-registry-failback-retry");
                t.setDaemon(true);
                return t;
            });

    private final Set<URL> failedRegistered = new ConcurrentHashSet<>();
    private final Set<URL> failedUnregistered = new ConcurrentHashSet<>();
    private final ConcurrentMap<URL, ConcurrentHashSet<NotifyListener>> failedSubscribed = new ConcurrentHashMap<>();
    private final ConcurrentMap<URL, ConcurrentHashSet<NotifyListener>> failedUnsubscribed = new ConcurrentHashMap<>();

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
            return super.discover(url);
        } catch (Exception e) {
            log.warn("Failed to discover url:{} in registry ({})", url, getUrl(), e);
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
        Set<NotifyListener> listeners = failedSubscribed.get(url);
        if (listeners == null) {
            failedSubscribed.putIfAbsent(url, new ConcurrentHashSet<>());
            listeners = failedSubscribed.get(url);
        }
        listeners.add(listener);
    }

    private void addFailedUnsubscribed(URL url, NotifyListener listener) {
        Set<NotifyListener> listeners = failedUnsubscribed.get(url);
        if (listeners == null) {
            failedUnsubscribed.putIfAbsent(url, new ConcurrentHashSet<>());
            listeners = failedUnsubscribed.get(url);
        }
        listeners.add(listener);
    }

    private void retry() {
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

    private void retrySubscriptions(ConcurrentMap<URL, ConcurrentHashSet<NotifyListener>> failedMap,
                                    boolean subscribe) {
        // Drop entries whose listeners have all been handled
        failedMap.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
        if (failedMap.isEmpty()) {
            return;
        }

        log.info("[{}] Retry {} {}", registryClassName, subscribe ? "subscribe" : "unsubscribe", failedMap);
        for (Map.Entry<URL, ConcurrentHashSet<NotifyListener>> entry : failedMap.entrySet()) {
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