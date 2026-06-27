package org.hongxi.jaws.registry.zookeeper;

import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.CreateMode;
import org.hongxi.jaws.closeable.Closeable;
import org.hongxi.jaws.closeable.ShutdownHook;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.util.ConcurrentHashSet;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.support.command.CommandFailbackRegistry;
import org.hongxi.jaws.registry.support.command.CommandListener;
import org.hongxi.jaws.registry.support.command.ServiceListener;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by shenhongxi on 2021/4/24.
 */
public class ZookeeperRegistry extends CommandFailbackRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private final ReentrantLock clientLock = new ReentrantLock();
    private final ReentrantLock serverLock = new ReentrantLock();
    private final CuratorFramework curator;
    private Set<URL> availableServices = new ConcurrentHashSet<>();
    private ConcurrentHashMap<URL, ConcurrentHashMap<ServiceListener, CuratorCache>> serviceListeners = new ConcurrentHashMap<>();
    private ConcurrentHashMap<URL, ConcurrentHashMap<CommandListener, CuratorCache>> commandListeners = new ConcurrentHashMap<>();

    public ZookeeperRegistry(URL url, CuratorFramework client) {
        super(url);
        this.curator = client;
        ConnectionStateListener connectionStateListener = (curatorFramework, connectionState) -> {
            if (connectionState == ConnectionState.RECONNECTED) {
                log.info("zkRegistry get reconnected notify.");
                reconnectService();
                reconnectClient();
            }
        };
        curator.getConnectionStateListenable().addListener(connectionStateListener);
        ShutdownHook.registerShutdownHook(this);
    }

    public ConcurrentHashMap<URL, ConcurrentHashMap<ServiceListener, CuratorCache>> getServiceListeners() {
        return serviceListeners;
    }

    public ConcurrentHashMap<URL, ConcurrentHashMap<CommandListener, CuratorCache>> getCommandListeners() {
        return commandListeners;
    }

    @Override
    protected void subscribeService(final URL url, final ServiceListener serviceListener) {
        try {
            clientLock.lock();
            ConcurrentHashMap<ServiceListener, CuratorCache> childChangeListeners = serviceListeners.get(url);
            if (childChangeListeners == null) {
                serviceListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                childChangeListeners = serviceListeners.get(url);
            }
            CuratorCache curatorCache = childChangeListeners.get(serviceListener);
            if (curatorCache == null) {
                String serverTypePath = ZkUtils.toNodeTypePath(url, ZkNodeType.AVAILABLE_SERVER);
                curatorCache = CuratorCache.build(curator, serverTypePath);
                curatorCache.listenable().addListener(new CuratorCacheListener() {
                    @Override
                    public void event(Type type, ChildData oldData, ChildData data) {
                        if (type == Type.NODE_CREATED || type == Type.NODE_DELETED) {
                            try {
                                List<String> currentChildren = curator.getChildren().forPath(serverTypePath);
                                serviceListener.notifyService(url, getUrl(), nodeChildrenToUrls(url, serverTypePath, currentChildren));
                                log.info("[ZookeeperRegistry] service list change: path={}, currentChildren={}", serverTypePath, currentChildren);
                            } catch (Exception e) {
                                log.warn("[ZookeeperRegistry] failed to get children for path {}", serverTypePath, e);
                            }
                        }
                    }
                });
                childChangeListeners.putIfAbsent(serviceListener, curatorCache);
                curatorCache = childChangeListeners.get(serviceListener);
                curatorCache.start();
            }

            try {
                // 防止旧节点未正常注销
                removeNode(url, ZkNodeType.CLIENT);
                createNode(url, ZkNodeType.CLIENT);
            } catch (Exception e) {
                log.warn("[ZookeeperRegistry] subscribe service: create node error, path={}, msg={}", ZkUtils.toNodePath(url, ZkNodeType.CLIENT), e.getMessage());
            }

            log.info("[ZookeeperRegistry] subscribe service: path={}, info={}", ZkUtils.toNodePath(url, ZkNodeType.AVAILABLE_SERVER), url.toFullStr());
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to subscribe %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected void subscribeCommand(final URL url, final CommandListener commandListener) {
        try {
            clientLock.lock();
            ConcurrentHashMap<CommandListener, CuratorCache> dataChangeListeners = commandListeners.get(url);
            if (dataChangeListeners == null) {
                commandListeners.putIfAbsent(url, new ConcurrentHashMap<>());
                dataChangeListeners = commandListeners.get(url);
            }
            CuratorCache curatorCache = dataChangeListeners.get(commandListener);
            if (curatorCache == null) {
                final String commandPath = ZkUtils.toCommandPath(url);
                curatorCache = CuratorCache.build(curator, commandPath);
                curatorCache.listenable().addListener(new CuratorCacheListener() {
                    @Override
                    public void event(Type type, ChildData oldData, ChildData data) {
                        if (type == Type.NODE_CHANGED || type == Type.NODE_CREATED) {
                            String command = data == null || data.getData() == null ? null : new String(data.getData(), StandardCharsets.UTF_8);
                            commandListener.notifyCommand(url, command);
                            log.info("[ZookeeperRegistry] command data change: path={}, command={}", commandPath, command);
                        } else if (type == Type.NODE_DELETED) {
                            commandListener.notifyCommand(url, null);
                            log.info("[ZookeeperRegistry] command deleted: path={}", commandPath);
                        }
                    }
                });
                dataChangeListeners.putIfAbsent(commandListener, curatorCache);
                curatorCache = dataChangeListeners.get(commandListener);
                curatorCache.start();
            }

            String commandPath = ZkUtils.toCommandPath(url);
            log.info("[ZookeeperRegistry] subscribe command: path={}, info={}", commandPath, url.toFullStr());
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to subscribe %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected void unsubscribeService(URL url, ServiceListener serviceListener) {
        try {
            clientLock.lock();
            Map<ServiceListener, CuratorCache> childChangeListeners = serviceListeners.get(url);
            if (childChangeListeners != null) {
                CuratorCache curatorCache = childChangeListeners.get(serviceListener);
                if (curatorCache != null) {
                    curatorCache.close();
                    childChangeListeners.remove(serviceListener);
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to unsubscribe service %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected void unsubscribeCommand(URL url, CommandListener commandListener) {
        try {
            clientLock.lock();
            Map<CommandListener, CuratorCache> dataChangeListeners = commandListeners.get(url);
            if (dataChangeListeners != null) {
                CuratorCache curatorCache = dataChangeListeners.get(commandListener);
                if (curatorCache != null) {
                    curatorCache.close();
                    dataChangeListeners.remove(commandListener);
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to unsubscribe command %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected List<URL> discoverService(URL url) {
        try {
            String parentPath = ZkUtils.toNodeTypePath(url, ZkNodeType.AVAILABLE_SERVER);
            List<String> currentChildren = new ArrayList<>();
            if (curator.checkExists().forPath(parentPath) != null) {
                currentChildren = curator.getChildren().forPath(parentPath);
            }
            return nodeChildrenToUrls(url, parentPath, currentChildren);
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to discover service %s from zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        }
    }

    @Override
    protected String discoverCommand(URL url) {
        try {
            String commandPath = ZkUtils.toCommandPath(url);
            String command = "";
            if (curator.checkExists().forPath(commandPath) != null) {
                byte[] data = curator.getData().forPath(commandPath);
                if (data != null) {
                    command = new String(data, StandardCharsets.UTF_8);
                }
            }
            return command;
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to discover command %s from zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()));
        }
    }

    @Override
    protected void doRegister(URL url) {
        try {
            serverLock.lock();
            // 防止旧节点未正常注销
            removeNode(url, ZkNodeType.AVAILABLE_SERVER);
            removeNode(url, ZkNodeType.UNAVAILABLE_SERVER);
            createNode(url, ZkNodeType.UNAVAILABLE_SERVER);
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to register %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doUnregister(URL url) {
        try {
            serverLock.lock();
            removeNode(url, ZkNodeType.AVAILABLE_SERVER);
            removeNode(url, ZkNodeType.UNAVAILABLE_SERVER);
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to unregister %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doAvailable(URL url) {
        try {
            serverLock.lock();
            if (url == null) {
                availableServices.addAll(getRegisteredServiceUrls());
                for (URL u : getRegisteredServiceUrls()) {
                    removeNode(u, ZkNodeType.AVAILABLE_SERVER);
                    removeNode(u, ZkNodeType.UNAVAILABLE_SERVER);
                    createNode(u, ZkNodeType.AVAILABLE_SERVER);
                }
            } else {
                availableServices.add(url);
                removeNode(url, ZkNodeType.AVAILABLE_SERVER);
                removeNode(url, ZkNodeType.UNAVAILABLE_SERVER);
                createNode(url, ZkNodeType.AVAILABLE_SERVER);
            }
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doUnavailable(URL url) {
        try {
            serverLock.lock();
            if (url == null) {
                availableServices.removeAll(getRegisteredServiceUrls());
                for (URL u : getRegisteredServiceUrls()) {
                    removeNode(u, ZkNodeType.AVAILABLE_SERVER);
                    removeNode(u, ZkNodeType.UNAVAILABLE_SERVER);
                    createNode(u, ZkNodeType.UNAVAILABLE_SERVER);
                }
            } else {
                availableServices.remove(url);
                removeNode(url, ZkNodeType.AVAILABLE_SERVER);
                removeNode(url, ZkNodeType.UNAVAILABLE_SERVER);
                createNode(url, ZkNodeType.UNAVAILABLE_SERVER);
            }
        } finally {
            serverLock.unlock();
        }
    }

    private List<URL> nodeChildrenToUrls(URL url, String parentPath, List<String> currentChildren) {
        List<URL> urls = new ArrayList<>();
        if (currentChildren != null) {
            for (String node : currentChildren) {
                String nodePath = parentPath + JawsConstants.PATH_SEPARATOR + node;
                String data = null;
                try {
                    byte[] bytes = curator.getData().forPath(nodePath);
                    if (bytes != null) {
                        data = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    log.warn("get zk data failed! {}", e.getMessage());
                }
                URL newurl = null;
                if (StringUtils.isNotBlank(data)) {
                    try {
                        newurl = URL.valueOf(data);
                    } catch (Exception e) {
                        log.warn("Found malformed urls from ZookeeperRegistry, path={}", nodePath, e);
                    }
                }
                if (newurl == null) {
                    newurl = url.createCopy();
                    String host = "";
                    int port = 80;
                    if (node.contains(":")) {
                        String[] hp = node.split(":");
                        if (hp.length > 1) {
                            host = hp[0];
                            try {
                                port = Integer.parseInt(hp[1]);
                            } catch (Exception ignore) {
                            }
                        }
                    } else {
                        host = node;
                    }
                    newurl.setHost(host);
                    newurl.setPort(port);
                }
                urls.add(newurl);
            }
        }
        return urls;
    }

    private void createNode(URL url, ZkNodeType nodeType) {
        try {
            String nodeTypePath = ZkUtils.toNodeTypePath(url, nodeType);
            if (curator.checkExists().forPath(nodeTypePath) == null) {
                curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(nodeTypePath);
            }
            curator.create().withMode(CreateMode.EPHEMERAL)
                    .forPath(ZkUtils.toNodePath(url, nodeType), url.toFullStr().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void removeNode(URL url, ZkNodeType nodeType) {
        try {
            String nodePath = ZkUtils.toNodePath(url, nodeType);
            if (curator.checkExists().forPath(nodePath) != null) {
                curator.delete().forPath(nodePath);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void reconnectService() {
        Collection<URL> allRegisteredServices = getRegisteredServiceUrls();
        if (allRegisteredServices != null && !allRegisteredServices.isEmpty()) {
            try {
                serverLock.lock();
                for (URL url : getRegisteredServiceUrls()) {
                    doRegister(url);
                }
                log.info("[{}] reconnect: register services {}", registryClassName, allRegisteredServices);

                for (URL url : availableServices) {
                    if (!getRegisteredServiceUrls().contains(url)) {
                        log.warn("reconnect url not register. url:{}", url);
                        continue;
                    }
                    doAvailable(url);
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
                for (Map.Entry<URL, ConcurrentHashMap<ServiceListener, CuratorCache>> entry : serviceListeners.entrySet()) {
                    URL url = entry.getKey();
                    ConcurrentHashMap<ServiceListener, CuratorCache> childChangeListeners = serviceListeners.get(url);
                    if (childChangeListeners != null) {
                        for (Map.Entry<ServiceListener, CuratorCache> e : childChangeListeners.entrySet()) {
                            subscribeService(url, e.getKey());
                        }
                    }
                }
                for (Map.Entry<URL, ConcurrentHashMap<CommandListener, CuratorCache>> entry : commandListeners.entrySet()) {
                    URL url = entry.getKey();
                    ConcurrentHashMap<CommandListener, CuratorCache> dataChangeListeners = commandListeners.get(url);
                    if (dataChangeListeners != null) {
                        for (Map.Entry<CommandListener, CuratorCache> e : dataChangeListeners.entrySet()) {
                            subscribeCommand(url, e.getKey());
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
        curator.close();
    }
}