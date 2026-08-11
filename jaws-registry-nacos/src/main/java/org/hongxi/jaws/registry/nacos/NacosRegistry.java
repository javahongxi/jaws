package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.lifecycle.Closeable;
import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.ConfigListener;
import org.hongxi.jaws.registry.support.command.CommandFailbackRegistry;
import org.hongxi.jaws.registry.support.command.CommandListener;
import org.hongxi.jaws.registry.support.command.ServiceListener;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nacos-based registry implementation.
 * <p>
 * Maps Jaws registry operations to Nacos NamingService:
 * <ul>
 *   <li>Service registration: register Nacos instances with metadata containing full URL</li>
 *   <li>Service discovery: query all instances and convert back to URLs</li>
 *   <li>Service subscription: use Nacos subscribe to watch instance changes</li>
 *   <li>Command: stored via Nacos ConfigService as dataId=jaws-command</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class NacosRegistry extends CommandFailbackRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistry.class);

    private static final String METADATA_KEY_FULL_URL = "fullUrl";

    private final NamingService namingService;
    private final ConfigService configService;
    private final ConcurrentHashMap<URL, ConcurrentHashMap<ServiceListener, EventListener>> serviceListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<URL, ConcurrentHashMap<CommandListener, Listener>> commandListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<URL, ConcurrentHashMap<ConfigListener, Listener>> configListeners = new ConcurrentHashMap<>();
    private final ReentrantLock clientLock = new ReentrantLock();
    private final ReentrantLock serverLock = new ReentrantLock();

    public NacosRegistry(URL url, NamingService namingService, ConfigService configService) {
        super(url);
        this.namingService = namingService;
        this.configService = configService;
        ShutdownHook.registerShutdownHook(this);
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

        String serviceName = NacosPathUtils.toServiceName(url);
        String group = NacosPathUtils.toGroup(url);
        log.info("[NacosRegistry] subscribe service: serviceName={}, group={}, info={}",
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

    private void subscribeCommandInternal(URL url, CommandListener commandListener) {
        ConcurrentHashMap<CommandListener, Listener> listeners = commandListeners.get(url);
        if (listeners == null) {
            commandListeners.putIfAbsent(url, new ConcurrentHashMap<>());
            listeners = commandListeners.get(url);
        }
        Listener existing = listeners.get(commandListener);
        if (existing == null) {
            String dataId = NacosPathUtils.toCommandDataId(url);
            String group = NacosPathUtils.toCommandGroup(url);
            Listener nacosListener = new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    commandListener.notifyCommand(url, configInfo);
                    log.info("[NacosRegistry] command change: dataId={}, group={}, command={}",
                            dataId, group, configInfo);
                }
            };
            listeners.putIfAbsent(commandListener, nacosListener);
            existing = listeners.get(commandListener);
            try {
                configService.addListener(dataId, group, existing);
            } catch (Exception e) {
                throw new JawsFrameworkException(
                        String.format("Failed to subscribe command %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
            }
        }

        log.info("[NacosRegistry] subscribe command: dataId={}, group={}",
                NacosPathUtils.toCommandDataId(url), NacosPathUtils.toCommandGroup(url));
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
            Map<CommandListener, Listener> listeners = commandListeners.get(url);
            if (listeners != null) {
                Listener nacosListener = listeners.remove(commandListener);
                if (nacosListener != null) {
                    String dataId = NacosPathUtils.toCommandDataId(url);
                    String group = NacosPathUtils.toCommandGroup(url);
                    configService.removeListener(dataId, group, nacosListener);
                }
            }
        } catch (Exception e) {
            log.warn("[NacosRegistry] unsubscribe command failed: dataId={}, msg={}",
                    NacosPathUtils.toCommandDataId(url), e.getMessage());
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
            String dataId = NacosPathUtils.toCommandDataId(url);
            String group = NacosPathUtils.toCommandGroup(url);
            String content = configService.getConfig(dataId, group, 5000);
            return content != null ? content : "";
        } catch (Exception e) {
            log.warn("[NacosRegistry] discover command failed: dataId={}, msg={}",
                    NacosPathUtils.toCommandDataId(url), e.getMessage());
            return "";
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
            log.debug("[NacosRegistry] deregister instance failed, serviceName={}, msg={}",
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

    // ---- dynamic config (Nacos ConfigService) ----

    @Override
    protected void doSubscribeConfig(URL url, ConfigListener listener) {
        ConcurrentHashMap<ConfigListener, Listener> listeners = configListeners.get(url);
        if (listeners == null) {
            configListeners.putIfAbsent(url, new ConcurrentHashMap<>());
            listeners = configListeners.get(url);
        }
        Listener existing = listeners.get(listener);
        if (existing == null) {
            String dataId = NacosPathUtils.toConfigDataId(url);
            String group = NacosPathUtils.toConfigGroup(url);
            Listener nacosListener = new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    listener.notifyConfig(url, configInfo);
                    log.info("[NacosRegistry] config change: dataId={}, group={}, config={}",
                            dataId, group, configInfo);
                }
            };
            listeners.putIfAbsent(listener, nacosListener);
            existing = listeners.get(listener);
            try {
                configService.addListener(dataId, group, existing);
            } catch (Exception e) {
                throw new JawsFrameworkException(
                        String.format("Failed to subscribe config %s to nacos(%s), cause: %s", url, getUrl(), e.getMessage()), e);
            }
        }
        log.info("[NacosRegistry] subscribe config: dataId={}, group={}",
                NacosPathUtils.toConfigDataId(url), NacosPathUtils.toConfigGroup(url));
    }

    @Override
    protected void doUnsubscribeConfig(URL url, ConfigListener listener) {
        try {
            Map<ConfigListener, Listener> listeners = configListeners.get(url);
            if (listeners != null) {
                Listener nacosListener = listeners.remove(listener);
                if (nacosListener != null) {
                    String dataId = NacosPathUtils.toConfigDataId(url);
                    String group = NacosPathUtils.toConfigGroup(url);
                    configService.removeListener(dataId, group, nacosListener);
                }
            }
        } catch (Exception e) {
            log.warn("[NacosRegistry] unsubscribe config failed: dataId={}, msg={}",
                    NacosPathUtils.toConfigDataId(url), e.getMessage());
        }
    }

    @Override
    protected String doDiscoverConfig(URL url) {
        try {
            String dataId = NacosPathUtils.toConfigDataId(url);
            String group = NacosPathUtils.toConfigGroup(url);
            String content = configService.getConfig(dataId, group, 5000);
            return content != null ? content : "";
        } catch (Exception e) {
            log.warn("[NacosRegistry] discover config failed: dataId={}, msg={}",
                    NacosPathUtils.toConfigDataId(url), e.getMessage());
            return "";
        }
    }
}
