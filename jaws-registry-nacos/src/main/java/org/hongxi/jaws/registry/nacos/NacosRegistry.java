package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.closeable.Closeable;
import org.hongxi.jaws.closeable.ShutdownHook;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.support.command.CommandFailbackRegistry;
import org.hongxi.jaws.registry.support.command.CommandListener;
import org.hongxi.jaws.registry.support.command.ServiceListener;
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
 *   <li>Command: stored as a special Nacos service with command data in instance metadata</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class NacosRegistry extends CommandFailbackRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistry.class);

    private static final String METADATA_KEY_FULL_URL = "fullUrl";
    private static final String METADATA_KEY_NODE_TYPE = "nodeType";
    private static final String NODE_TYPE_AVAILABLE = "available";
    private static final String NODE_TYPE_UNAVAILABLE = "unavailable";
    private static final String NODE_TYPE_CLIENT = "client";

    private static final String COMMAND_INSTANCE_ID = "command";
    private static final String METADATA_KEY_COMMAND = "command";

    private final NamingService namingService;
    private final Set<URL> availableServices = new ConcurrentHashSet<>();
    private final ConcurrentHashMap<URL, ConcurrentHashMap<ServiceListener, EventListener>> serviceListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<URL, ConcurrentHashMap<CommandListener, EventListener>> commandListeners = new ConcurrentHashMap<>();
    private final ReentrantLock clientLock = new ReentrantLock();
    private final ReentrantLock serverLock = new ReentrantLock();

    public NacosRegistry(URL url, NamingService namingService) {
        super(url);
        this.namingService = namingService;
        ShutdownHook.registerShutdownHook(this);
    }

    public ConcurrentHashMap<URL, ConcurrentHashMap<ServiceListener, EventListener>> getServiceListeners() {
        return serviceListeners;
    }

    public ConcurrentHashMap<URL, ConcurrentHashMap<CommandListener, EventListener>> getCommandListeners() {
        return commandListeners;
    }

    /* Internal method without locking, called by reconnect to avoid lock reentrancy */
    private void subscribeServiceInternal(URL url, ServiceListener serviceListener) {
        ConcurrentHashMap<ServiceListener, EventListener> listeners = serviceListeners.get(url);
        if (listeners == null) {
            serviceListeners.putIfAbsent(url, new ConcurrentHashMap<>());
            listeners = serviceListeners.get(url);
        }
        EventListener eventListener = listeners.get(serviceListener);
        if (eventListener == null) {
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            eventListener = event -> {
                if (event instanceof NamingEvent namingEvent) {
                    List<Instance> instances = namingEvent.getInstances();
                    List<URL> urls = instancesToUrls(url, instances);
                    serviceListener.notifyService(url, getUrl(), urls);
                    log.info("[NacosRegistry] service list change: serviceName={}, group={}, instanceCount={}",
                            serviceName, group, instances != null ? instances.size() : 0);
                }
            };
            listeners.putIfAbsent(serviceListener, eventListener);
            eventListener = listeners.get(serviceListener);
            try {
                namingService.subscribe(serviceName, group, eventListener);
            } catch (Exception e) {
                throw new JawsFrameworkException(
                        String.format("Failed to subscribe %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
            }
        }

        try {
            // 防止旧节点未正常注销
            removeInstance(url, NODE_TYPE_CLIENT);
            registerInstance(url, NODE_TYPE_CLIENT);
        } catch (Exception e) {
            log.warn("[NacosRegistry] subscribe service: register client instance error, serviceName={}, msg={}",
                    NacosPathUtils.toServiceName(url), e.getMessage());
        }

        String serviceName = NacosPathUtils.toServiceName(url);
        String group = NacosPathUtils.toGroup(url);
        log.info("[NacosRegistry] subscribe service: serviceName={}, group={}, info={}",
                serviceName, group, url.toFullStr());
    }

    @Override
    protected void subscribeService(URL url, ServiceListener serviceListener) {
        try {
            clientLock.lock();
            subscribeServiceInternal(url, serviceListener);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to subscribe %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    /* Internal method without locking, called by reconnect to avoid lock reentrancy */
    private void subscribeCommandInternal(URL url, CommandListener commandListener) {
        ConcurrentHashMap<CommandListener, EventListener> listeners = commandListeners.get(url);
        if (listeners == null) {
            commandListeners.putIfAbsent(url, new ConcurrentHashMap<>());
            listeners = commandListeners.get(url);
        }
        EventListener eventListener = listeners.get(commandListener);
        if (eventListener == null) {
            String serviceName = NacosPathUtils.toCommandServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            eventListener = event -> {
                if (event instanceof NamingEvent namingEvent) {
                    List<Instance> instances = namingEvent.getInstances();
                    String command = extractCommand(instances);
                    commandListener.notifyCommand(url, command);
                    log.info("[NacosRegistry] command change: serviceName={}, group={}, command={}",
                            serviceName, group, command);
                }
            };
            listeners.putIfAbsent(commandListener, eventListener);
            eventListener = listeners.get(commandListener);
            try {
                namingService.subscribe(serviceName, group, eventListener);
            } catch (Exception e) {
                throw new JawsFrameworkException(
                        String.format("Failed to subscribe command %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
            }
        }

        String serviceName = NacosPathUtils.toCommandServiceName(url);
        String group = NacosPathUtils.toGroup(url);
        log.info("[NacosRegistry] subscribe command: serviceName={}, group={}, info={}",
                serviceName, group, url.toFullStr());
    }

    @Override
    protected void subscribeCommand(URL url, CommandListener commandListener) {
        try {
            clientLock.lock();
            subscribeCommandInternal(url, commandListener);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to subscribe command %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected void unsubscribeService(URL url, ServiceListener serviceListener) {
        try {
            clientLock.lock();
            Map<ServiceListener, EventListener> listeners = serviceListeners.get(url);
            if (listeners != null) {
                EventListener eventListener = listeners.get(serviceListener);
                if (eventListener != null) {
                    String serviceName = NacosPathUtils.toServiceName(url);
                    String group = NacosPathUtils.toGroup(url);
                    namingService.unsubscribe(serviceName, group, eventListener);
                    listeners.remove(serviceListener);
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to unsubscribe service %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected void unsubscribeCommand(URL url, CommandListener commandListener) {
        try {
            clientLock.lock();
            Map<CommandListener, EventListener> listeners = commandListeners.get(url);
            if (listeners != null) {
                EventListener eventListener = listeners.get(commandListener);
                if (eventListener != null) {
                    String serviceName = NacosPathUtils.toCommandServiceName(url);
                    String group = NacosPathUtils.toGroup(url);
                    namingService.unsubscribe(serviceName, group, eventListener);
                    listeners.remove(commandListener);
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to unsubscribe command %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected List<URL> discoverService(URL url) {
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
    protected String discoverCommand(URL url) {
        try {
            String serviceName = NacosPathUtils.toCommandServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            List<Instance> instances = namingService.getAllInstances(serviceName, group);
            return extractCommand(instances);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to discover command %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()));
        }
    }

    /* Internal method without locking, called by reconnect to avoid lock reentrancy */
    private void doRegisterInternal(URL url) {
        // 防止旧节点未正常注销
        removeInstance(url, NODE_TYPE_AVAILABLE);
        removeInstance(url, NODE_TYPE_UNAVAILABLE);
        registerInstance(url, NODE_TYPE_UNAVAILABLE);
    }

    @Override
    protected void doRegister(URL url) {
        try {
            serverLock.lock();
            doRegisterInternal(url);
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
            removeInstance(url, NODE_TYPE_AVAILABLE);
            removeInstance(url, NODE_TYPE_UNAVAILABLE);
        } catch (Throwable e) {
            throw new JawsFrameworkException(
                    String.format("Failed to unregister %s from nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    /* Internal method without locking, called by reconnect to avoid lock reentrancy */
    private void doAvailableInternal(URL url) {
        if (url == null) {
            availableServices.addAll(getRegisteredServiceUrls());
            for (URL u : getRegisteredServiceUrls()) {
                removeInstance(u, NODE_TYPE_AVAILABLE);
                removeInstance(u, NODE_TYPE_UNAVAILABLE);
                registerInstance(u, NODE_TYPE_AVAILABLE);
            }
        } else {
            availableServices.add(url);
            removeInstance(url, NODE_TYPE_AVAILABLE);
            removeInstance(url, NODE_TYPE_UNAVAILABLE);
            registerInstance(url, NODE_TYPE_AVAILABLE);
        }
    }

    @Override
    protected void doAvailable(URL url) {
        try {
            serverLock.lock();
            doAvailableInternal(url);
        } finally {
            serverLock.unlock();
        }
    }

    /* Internal method without locking, called by reconnect to avoid lock reentrancy */
    private void doUnavailableInternal(URL url) {
        if (url == null) {
            availableServices.removeAll(getRegisteredServiceUrls());
            for (URL u : getRegisteredServiceUrls()) {
                removeInstance(u, NODE_TYPE_AVAILABLE);
                removeInstance(u, NODE_TYPE_UNAVAILABLE);
                registerInstance(u, NODE_TYPE_UNAVAILABLE);
            }
        } else {
            availableServices.remove(url);
            removeInstance(url, NODE_TYPE_AVAILABLE);
            removeInstance(url, NODE_TYPE_UNAVAILABLE);
            registerInstance(url, NODE_TYPE_UNAVAILABLE);
        }
    }

    @Override
    protected void doUnavailable(URL url) {
        try {
            serverLock.lock();
            doUnavailableInternal(url);
        } finally {
            serverLock.unlock();
        }
    }

    private void registerInstance(URL url, String nodeType) {
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
            metadata.put(METADATA_KEY_NODE_TYPE, nodeType);
            instance.setMetadata(metadata);
            namingService.registerInstance(serviceName, group, instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void removeInstance(URL url, String nodeType) {
        try {
            String serviceName = NacosPathUtils.toServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            Map<String, String> metadata = new HashMap<>();
            metadata.put(METADATA_KEY_NODE_TYPE, nodeType);
            instance.setMetadata(metadata);
            namingService.deregisterInstance(serviceName, group, instance);
        } catch (Exception e) {
            // deregister may fail if instance not exists, just log and ignore
            log.debug("[NacosRegistry] deregister instance failed, serviceName={}, nodeType={}, msg={}",
                    NacosPathUtils.toServiceName(url), nodeType, e.getMessage());
        }
    }

    private void registerCommandInstance(URL url, String command) {
        try {
            String serviceName = NacosPathUtils.toCommandServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            instance.setHealthy(true);
            instance.setEphemeral(true);
            Map<String, String> metadata = new HashMap<>();
            metadata.put(METADATA_KEY_COMMAND, command != null ? command : "");
            instance.setMetadata(metadata);
            namingService.registerInstance(serviceName, group, instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void removeCommandInstance(URL url) {
        try {
            String serviceName = NacosPathUtils.toCommandServiceName(url);
            String group = NacosPathUtils.toGroup(url);
            Instance instance = new Instance();
            instance.setIp(url.getHost());
            instance.setPort(url.getPort());
            namingService.deregisterInstance(serviceName, group, instance);
        } catch (Exception e) {
            log.debug("[NacosRegistry] deregister command instance failed, serviceName={}, msg={}",
                    NacosPathUtils.toCommandServiceName(url), e.getMessage());
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

    private String extractCommand(List<Instance> instances) {
        if (instances != null) {
            for (Instance instance : instances) {
                Map<String, String> metadata = instance.getMetadata();
                if (metadata != null && metadata.containsKey(METADATA_KEY_COMMAND)) {
                    return metadata.get(METADATA_KEY_COMMAND);
                }
            }
        }
        return "";
    }

    private void reconnectService() {
        Collection<URL> allRegisteredServices = getRegisteredServiceUrls();
        if (allRegisteredServices != null && !allRegisteredServices.isEmpty()) {
            try {
                serverLock.lock();
                for (URL url : getRegisteredServiceUrls()) {
                    doRegisterInternal(url);
                }
                log.info("[{}] reconnect: register services {}", registryClassName, allRegisteredServices);

                for (URL url : availableServices) {
                    if (!getRegisteredServiceUrls().contains(url)) {
                        log.warn("reconnect url not registered. url:{}", url);
                        continue;
                    }
                    doAvailableInternal(url);
                }
                log.info("[{}] reconnect: available services {}", registryClassName, availableServices);
            } finally {
                serverLock.unlock();
            }
        }
    }

    private void reconnectClient() {
        if (serviceListeners != null && !serviceListeners.isEmpty()) {
            try {
                clientLock.lock();
                for (Map.Entry<URL, ConcurrentHashMap<ServiceListener, EventListener>> entry : serviceListeners.entrySet()) {
                    URL url = entry.getKey();
                    ConcurrentHashMap<ServiceListener, EventListener> listeners = serviceListeners.get(url);
                    if (listeners != null) {
                        for (Map.Entry<ServiceListener, EventListener> e : listeners.entrySet()) {
                            subscribeServiceInternal(url, e.getKey());
                        }
                    }
                }
                for (Map.Entry<URL, ConcurrentHashMap<CommandListener, EventListener>> entry : commandListeners.entrySet()) {
                    URL url = entry.getKey();
                    ConcurrentHashMap<CommandListener, EventListener> listeners = commandListeners.get(url);
                    if (listeners != null) {
                        for (Map.Entry<CommandListener, EventListener> e : listeners.entrySet()) {
                            subscribeCommandInternal(url, e.getKey());
                        }
                    }
                }
                log.info("[{}] reconnect all clients", registryClassName);
            } finally {
                clientLock.unlock();
            }
        }
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
