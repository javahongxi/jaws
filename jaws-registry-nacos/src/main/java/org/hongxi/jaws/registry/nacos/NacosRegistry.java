package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.lifecycle.Closeable;
import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.FailbackRegistry;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nacos-based registry implementation.
 * <p>
 * Maps Jaws registry operations to Nacos NamingService:
 * <ul>
 *   <li>Service registration: register Nacos instances with metadata containing full URL</li>
 *   <li>Service discovery: query all instances and convert back to URLs</li>
 *   <li>Service subscription: use Nacos subscribe to watch instance changes</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class NacosRegistry extends FailbackRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistry.class);

    private static final String METADATA_KEY_FULL_URL = "fullUrl";

    private final NamingService namingService;
    private final ConcurrentHashMap<URL, ConcurrentHashMap<NotifyListener, EventListener>> serviceListeners = new ConcurrentHashMap<>();
    private final ReentrantLock clientLock = new ReentrantLock();
    private final ReentrantLock serverLock = new ReentrantLock();

    public NacosRegistry(URL url, NamingService namingService) {
        super(url);
        this.namingService = namingService;
        ShutdownHook.registerShutdownHook(this);
    }

    @Override
    protected void doSubscribe(URL url, NotifyListener listener) {
        try {
            clientLock.lock();
            subscribeServiceInternal(url, listener);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to subscribe %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    private void subscribeServiceInternal(URL url, NotifyListener listener) {
        ConcurrentHashMap<NotifyListener, EventListener> listeners = serviceListeners.get(url);
        if (listeners == null) {
            serviceListeners.putIfAbsent(url, new ConcurrentHashMap<>());
            listeners = serviceListeners.get(url);
        }
        EventListener eventListener = listeners.get(listener);
        if (eventListener == null) {
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            eventListener = event -> {
                if (event instanceof NamingEvent namingEvent) {
                    List<Instance> instances = namingEvent.getInstances();
                    List<URL> urls = instancesToUrls(url, instances);
                    listener.notify(getUrl(), urls);
                    log.info("[NacosRegistry] service list change: serviceName={}, group={}, instanceCount={}",
                            serviceName, group, instances != null ? instances.size() : 0);
                }
            };
            listeners.putIfAbsent(listener, eventListener);
            eventListener = listeners.get(listener);
            try {
                namingService.subscribe(serviceName, group, eventListener);
            } catch (Exception e) {
                throw new JawsFrameworkException(
                        String.format("Failed to subscribe %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
            }
        }

        String serviceName = NacosPathUtils.toServiceName(url);
        String group = NacosPathUtils.toGroup(url);
        log.info("[NacosRegistry] subscribe service: serviceName={}, group={}, info={}",
                serviceName, group, url.toFullStr());
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        try {
            clientLock.lock();
            Map<NotifyListener, EventListener> listeners = serviceListeners.get(url);
            if (listeners != null) {
                EventListener eventListener = listeners.remove(listener);
                if (eventListener != null) {
                    String serviceName = NacosPathUtils.toServiceName(url);
                    String group = NacosPathUtils.toGroup(url);
                    namingService.unsubscribe(serviceName, group, eventListener);
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to unsubscribe %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected List<URL> doDiscover(URL url) {
        try {
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            List<Instance> instances = namingService.getAllInstances(serviceName, group);
            return instancesToUrls(url, instances);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to discover service %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        }
    }

    @Override
    protected void doRegister(URL url) {
        try {
            serverLock.lock();
            // Remove stale nodes that may not have been properly unregistered
            removeInstance(url);
            registerInstance(url);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to register %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doUnregister(URL url) {
        try {
            serverLock.lock();
            removeInstance(url);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to unregister %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    private void registerInstance(URL url) {
        try {
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            instance.setHealthy(true);
            instance.setEphemeral(true);
            Map<String, String> metadata = new HashMap<>();
            metadata.put(METADATA_KEY_FULL_URL, url.toFullStr());
            instance.setMetadata(metadata);
            namingService.registerInstance(serviceName, group, instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void removeInstance(URL url) {
        try {
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            namingService.deregisterInstance(serviceName, group, instance);
        } catch (Exception e) {
            // deregister may fail if instance not exists, just log and ignore
            log.debug("[NacosRegistry] deregister instance failed: serviceName={}, msg={}",
                    NacosPathUtils.toServiceName(url), e.getMessage());
        }
    }

    private List<URL> instancesToUrls(URL refUrl, List<Instance> instances) {
        List<URL> urls = new ArrayList<>();
        if (instances != null) {
            for (Instance instance : instances) {
                Map<String, String> metadata = instance.getMetadata();
                String fullUrl = metadata != null ? metadata.get(METADATA_KEY_FULL_URL) : null;
                URL parsedUrl = null;
                if (StringUtils.isNotBlank(fullUrl)) {
                    try {
                        parsedUrl = URL.valueOf(fullUrl);
                    } catch (Exception e) {
                        log.warn("Found malformed urls from NacosRegistry, fullUrl={}", fullUrl, e);
                    }
                }
                if (parsedUrl == null) {
                    parsedUrl = refUrl.createCopy();
                    parsedUrl.setHost(instance.getIp());
                    parsedUrl.setPort(instance.getPort());
                }
                urls.add(parsedUrl);
            }
        }
        return urls;
    }

    @Override
    public void close() {
        try {
            namingService.shutDown();
        } catch (Exception e) {
            log.warn("[NacosRegistry] failed to shutdown namingService", e);
        }
    }
}
