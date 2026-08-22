package org.hongxi.jaws.registry.zookeeper;

import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.CreateMode;
import org.hongxi.jaws.lifecycle.Closeable;
import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.FailbackRegistry;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link FailbackRegistry} implementation backed by ZooKeeper via Curator.
 * Providers are registered as ephemeral {@link ZkNodeType#AVAILABLE_SERVER}
 * nodes carrying the full URL, and subscriptions watch the server path with
 * a {@code CuratorCache} to push service-list changes to
 * {@link NotifyListener}s.
 * <p>
 * On reconnection the registry re-registers all services and re-subscribes
 * all listeners, and it closes the Curator client via a shutdown hook.
 *
 * @see ZookeeperRegistryFactory
 * @see ZkUtils
 *
 * Created by shenhongxi on 2021/4/24.
 */
public class ZookeeperRegistry extends FailbackRegistry implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private final ReentrantLock clientLock = new ReentrantLock();
    private final ReentrantLock serverLock = new ReentrantLock();
    private final CuratorFramework curator;
    private final Map<URL, Map<NotifyListener, CuratorCache>> serviceListeners = new HashMap<>();

    public ZookeeperRegistry(URL url, CuratorFramework client) {
        super(url);
        this.curator = client;
        ConnectionStateListener connectionStateListener = (curatorFramework, connectionState) -> {
            if (connectionState == ConnectionState.RECONNECTED) {
                log.info("zkRegistry get reconnected notify.");
                reRegisterServices();
                reSubscribeServices();
            }
        };
        curator.getConnectionStateListenable().addListener(connectionStateListener);
        ShutdownHook.registerShutdownHook(this);
    }

    @Override
    protected void doRegister(URL url) {
        try {
            serverLock.lock();
            // Remove stale nodes that may not have been properly unregistered
            removeNode(url, ZkNodeType.AVAILABLE_SERVER);
            createNode(url, ZkNodeType.AVAILABLE_SERVER);
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
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to unregister %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            serverLock.unlock();
        }
    }

    @Override
    protected void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            clientLock.lock();
            subscribeServiceInternal(url, listener);
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to subscribe %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    private void subscribeServiceInternal(final URL url, final NotifyListener listener) {
        Map<NotifyListener, CuratorCache> childChangeListeners = serviceListeners.computeIfAbsent(url, k -> new HashMap<>());
        CuratorCache curatorCache = childChangeListeners.get(listener);
        if (curatorCache == null) {
            String serverTypePath = ZkUtils.toNodeTypePath(url, ZkNodeType.AVAILABLE_SERVER);
            curatorCache = CuratorCache.build(curator, serverTypePath);
            curatorCache.listenable().addListener(new CuratorCacheListener() {
                @Override
                public void event(Type type, ChildData oldData, ChildData data) {
                    if (type == Type.NODE_CREATED || type == Type.NODE_DELETED) {
                        try {
                            List<String> currentChildren = curator.getChildren().forPath(serverTypePath);
                            List<URL> urls = nodeChildrenToUrls(url, serverTypePath, currentChildren);
                            listener.notify(getUrl(), urls);
                            log.info("service list change: path={}, currentChildren={}", serverTypePath, currentChildren);
                        } catch (Exception e) {
                            log.warn("failed to get children for path {}", serverTypePath, e);
                        }
                    }
                }
            });
            childChangeListeners.put(listener, curatorCache);
            curatorCache.start();
        }

        try {
            // Remove stale nodes that may not have been properly unregistered
            removeNode(url, ZkNodeType.CLIENT);
            createNode(url, ZkNodeType.CLIENT);
        } catch (Exception e) {
            log.warn("subscribe service: create node error, path={}", ZkUtils.toNodePath(url, ZkNodeType.CLIENT), e);
        }

        log.info("subscribe service: path={}, info={}", ZkUtils.toNodePath(url, ZkNodeType.AVAILABLE_SERVER), url.toFullStr());
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        try {
            clientLock.lock();
            Map<NotifyListener, CuratorCache> childChangeListeners = serviceListeners.get(url);
            if (childChangeListeners != null) {
                CuratorCache curatorCache = childChangeListeners.remove(listener);
                if (curatorCache != null) {
                    curatorCache.close();
                }
            }
        } catch (Throwable e) {
            throw new JawsFrameworkException(String.format("Failed to unsubscribe %s to zookeeper(%s), cause: %s", url, getUrl(), e.getMessage()), e);
        } finally {
            clientLock.unlock();
        }
    }

    @Override
    protected List<URL> doDiscover(URL url) {
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
                    log.warn("failed to get zk data, path={}", nodePath, e);
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

    private void reRegisterServices() {
        Set<URL> registered = getRegistered();
        if (!registered.isEmpty()) {
            try {
                serverLock.lock();
                for (URL url : registered) {
                    // Remove stale nodes that may not have been properly unregistered
                    removeNode(url, ZkNodeType.AVAILABLE_SERVER);
                    createNode(url, ZkNodeType.AVAILABLE_SERVER);
                }
                log.info("reconnect: registered services {}", registered);
            } finally {
                serverLock.unlock();
            }
        }
    }

    private void reSubscribeServices() {
        if (!serviceListeners.isEmpty()) {
            try {
                clientLock.lock();
                for (Map.Entry<URL, Map<NotifyListener, CuratorCache>> entry : serviceListeners.entrySet()) {
                    URL url = entry.getKey();
                    Map<NotifyListener, CuratorCache> childChangeListeners = entry.getValue();
                    for (NotifyListener listener : childChangeListeners.keySet()) {
                        subscribeServiceInternal(url, listener);
                    }
                }
                log.info("reconnect all clients");
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