package org.hongxi.jaws.registry;

import org.hongxi.jaws.closable.ShutdownHook;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.support.AbstractRegistry;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Failback registry
 * 
 * Created by shenhongxi on 2021/4/23.
 */
public abstract class FailbackRegistry extends AbstractRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(FailbackRegistry.class);

    private Set<URL> failedRegistered = new ConcurrentHashSet<>();
    private Set<URL> failedUnregistered = new ConcurrentHashSet<>();
    private ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> failedSubscribed =
            new ConcurrentHashMap<>();
    private ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> failedUnsubscribed =
            new ConcurrentHashMap<>();

    private static ScheduledExecutorService retryExecutor = Executors.newScheduledThreadPool(1);
    
    static{
        ShutdownHook.registerShutdownHook(() -> {
            if(!retryExecutor.isShutdown()){
                retryExecutor.shutdown();
            }
        });
    }

    public FailbackRegistry(URL url) {
        super(url);
        long retryPeriod = url.getIntParameter(URLParamType.registryRetryPeriod.getName(), URLParamType.registryRetryPeriod.intValue());
        retryExecutor.scheduleAtFixedRate(() -> {
            try {
                retry();
            } catch (Exception e) {
                log.warn("[{}] False when retry in failback registry", registryClassName, e);
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
            if (isCheckingUrls(getUrl(), url)) {
                throw new JawsFrameworkException(String.format("[%s] false to registery %s to %s", registryClassName, url, getUrl()), e);
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
            if (isCheckingUrls(getUrl(), url)) {
                throw new JawsFrameworkException(String.format("[%s] false to unregistery %s to %s", registryClassName, url, getUrl()), e);
            }
            failedUnregistered.add(url);
        }
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        removeForFailedSubAndUnsub(url, listener);

        try {
            super.subscribe(url, listener);
        } catch (Exception e) {
            List<URL> cachedUrls = getCachedUrls(url);
            if (cachedUrls != null && cachedUrls.size() > 0) {
                listener.notify(getUrl(), cachedUrls);
            } else if (isCheckingUrls(getUrl(), url)) {
                log.warn("[{}] false to subscribe {} from {}", registryClassName, url, getUrl(), e);
                throw new JawsFrameworkException(String.format("[%s] false to subscribe %s from %s", registryClassName, url, getUrl()), e);
            }
            addToFailedMap(failedSubscribed, url, listener);
        }
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        removeForFailedSubAndUnsub(url, listener);

        try {
            super.unsubscribe(url, listener);
        } catch (Exception e) {
            if (isCheckingUrls(getUrl(), url)) {
                throw new JawsFrameworkException(String.format("[%s] false to unsubscribe %s from %s",
                        registryClassName, url, getUrl()), e);
            }
            addToFailedMap(failedUnsubscribed, url, listener);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<URL> discover(URL url) {
        try {
            return super.discover(url);
        } catch (Exception e) {
            // 如果discover失败，返回一个empty list吧，毕竟是个下行动作，
            log.warn("Failed to discover url:{} in registry ({})", url, getUrl(), e);
            return Collections.emptyList();
        }
    }

    private boolean isCheckingUrls(URL... urls) {
        for (URL url : urls) {
            if (!Boolean.parseBoolean(url.getParameter(URLParamType.check.getName(), URLParamType.check.value()))) {
                return false;
            }
        }
        return true;
    }

    private void removeForFailedSubAndUnsub(URL url, NotifyListener listener) {
        Set<NotifyListener> listeners = failedSubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
        listeners = failedUnsubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    private void addToFailedMap(ConcurrentHashMap<URL, ConcurrentHashSet<NotifyListener>> failedMap, URL url, NotifyListener listener) {
        Set<NotifyListener> listeners = failedMap.get(url);
        if (listeners == null) {
            failedMap.putIfAbsent(url, new ConcurrentHashSet<>());
            listeners = failedMap.get(url);
        }
        listeners.add(listener);
    }

    private void retry() {
        if (!failedRegistered.isEmpty()) {
            Set<URL> failed = new HashSet<URL>(failedRegistered);
            log.info("[{}] Retry register {}", registryClassName, failed);
            try {
                for (URL url : failed) {
                    super.register(url);
                    failedRegistered.remove(url);
                }
            } catch (Exception e) {
                log.warn("[{}] Failed to retry register, retry later, failedRegistered.size={}",
                        registryClassName, failedRegistered.size(), e);
            }

        }
        if (!failedUnregistered.isEmpty()) {
            Set<URL> failed = new HashSet<>(failedUnregistered);
            log.info("[{}] Retry unregister {}", registryClassName, failed);
            try {
                for (URL url : failed) {
                    super.unregister(url);
                    failedUnregistered.remove(url);
                }
            } catch (Exception e) {
                log.warn("[{}] Failed to retry unregister, retry later, failedUnregistered.size={}",
                        registryClassName, failedUnregistered.size(), e);
            }

        }
        if (!failedSubscribed.isEmpty()) {
            Map<URL, Set<NotifyListener>> failed = new HashMap<>(failedSubscribed);
            for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() == 0) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                log.info("[{}] Retry subscribe {}", registryClassName, failed);
                try {
                    for (Map.Entry<URL, Set<NotifyListener>> entry : failed.entrySet()) {
                        URL url = entry.getKey();
                        Set<NotifyListener> listeners = entry.getValue();
                        for (NotifyListener listener : listeners) {
                            super.subscribe(url, listener);
                            listeners.remove(listener);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[{}] Failed to retry subscribe, retry later, failedSubscribed.size={}",
                            registryClassName, failedSubscribed.size(), e);
                }
            }
        }
        if (!failedUnsubscribed.isEmpty()) {
            Map<URL, Set<NotifyListener>> failed = new HashMap<>(failedUnsubscribed);
            for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() == 0) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                log.info("[{}] Retry unsubscribe {}", registryClassName, failed);
                try {
                    for (Map.Entry<URL, Set<NotifyListener>> entry : failed.entrySet()) {
                        URL url = entry.getKey();
                        Set<NotifyListener> listeners = entry.getValue();
                        for (NotifyListener listener : listeners) {
                            super.unsubscribe(url, listener);
                            listeners.remove(listener);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[{}] Failed to retry unsubscribe, retry later, failedUnsubscribed.size={}",
                            registryClassName, failedUnsubscribed.size(), e);
                }
            }
        }
    }
}