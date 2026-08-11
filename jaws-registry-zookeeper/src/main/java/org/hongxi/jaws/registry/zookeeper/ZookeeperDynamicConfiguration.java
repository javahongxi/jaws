package org.hongxi.jaws.registry.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.config.configcenter.ConfigurationListener;
import org.hongxi.jaws.config.configcenter.DynamicConfiguration;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Zookeeper-based implementation of {@link DynamicConfiguration}.
 * <p>
 * Uses CuratorFramework to store and retrieve configuration values as ZK node data.
 * Supports remote config push via CuratorCache watcher mechanism.
 * <p>
 * Configuration mapping:
 * <ul>
 *   <li>key → ZK node path under {@code /jaws/dynamic-config/}</li>
 *   <li>value → node data (UTF-8 string)</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/8/11.
 */
@SpiMeta(name = "zookeeper")
public class ZookeeperDynamicConfiguration implements DynamicConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperDynamicConfiguration.class);

    private static final String CONFIG_ROOT = JawsConstants.ZOOKEEPER_REGISTRY_NAMESPACE + "/dynamic-config";

    private volatile CuratorFramework curator;
    private final ConcurrentMap<String, List<ConfigurationListener>> listenerMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CuratorCache> cacheMap = new ConcurrentHashMap<>();

    /**
     * Initialize with a registry URL. Extracts connection info to create CuratorFramework.
     */
    public void init(URL registryUrl) {
        int timeout = registryUrl.getIntParameter("connectTimeout", 1000);
        int sessionTimeout = registryUrl.getIntParameter("registrySessionTimeout", 30000);
        String username = registryUrl.getParameter("username");
        String password = registryUrl.getParameter("password");
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(registryUrl.getBackupAddress())
                .sessionTimeoutMs(sessionTimeout)
                .connectionTimeoutMs(timeout)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3));
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            String auth = username + ":" + password;
            builder.authorization("digest", auth.getBytes(StandardCharsets.UTF_8));
        }
        CuratorFramework client = builder.build();
        client.start();
        this.curator = client;
        ensureRootPath();
        log.info("[ZookeeperDynamicConfiguration] initialized with server={}", registryUrl.getBackupAddress());
    }

    /**
     * Initialize with an existing CuratorFramework (shared with ZookeeperRegistry).
     */
    public void init(CuratorFramework curator) {
        this.curator = curator;
        ensureRootPath();
    }

    private void ensureRootPath() {
        try {
            if (curator.checkExists().forPath(CONFIG_ROOT) == null) {
                curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(CONFIG_ROOT);
            }
        } catch (Exception e) {
            log.warn("[ZookeeperDynamicConfiguration] failed to ensure root path: {}", CONFIG_ROOT, e);
        }
    }

    private String toConfigPath(String key) {
        return CONFIG_ROOT + JawsConstants.PATH_SEPARATOR + key;
    }

    @Override
    public String getConfig(String key) {
        if (curator == null) {
            log.warn("[ZookeeperDynamicConfiguration] curator not initialized, returning null for key={}", key);
            return null;
        }
        try {
            String path = toConfigPath(key);
            if (curator.checkExists().forPath(path) == null) {
                return null;
            }
            byte[] data = curator.getData().forPath(path);
            return data != null ? new String(data, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            log.warn("[ZookeeperDynamicConfiguration] failed to get config: key={}, msg={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void setConfig(String key, String value) {
        if (curator == null) {
            log.warn("[ZookeeperDynamicConfiguration] curator not initialized, cannot set key={}", key);
            return;
        }
        try {
            String path = toConfigPath(key);
            byte[] data = value != null ? value.getBytes(StandardCharsets.UTF_8) : null;
            if (curator.checkExists().forPath(path) == null) {
                if (data != null) {
                    curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path, data);
                }
            } else {
                if (data != null) {
                    curator.setData().forPath(path, data);
                } else {
                    curator.delete().forPath(path);
                }
            }
        } catch (Exception e) {
            log.warn("[ZookeeperDynamicConfiguration] failed to set config: key={}, msg={}", key, e.getMessage());
        }
    }

    @Override
    public void addListener(String key, ConfigurationListener listener) {
        List<ConfigurationListener> listeners = Collections.synchronizedList(new ArrayList<>());
        List<ConfigurationListener> existing = listenerMap.putIfAbsent(key, listeners);
        if (existing == null) {
            listeners.add(listener);
            registerCacheListener(key);
        } else {
            existing.add(listener);
        }
    }

    @Override
    public void removeListener(String key, ConfigurationListener listener) {
        List<ConfigurationListener> listeners = listenerMap.get(key);
        if (listeners != null) {
            if (listener == null) {
                listeners.clear();
            } else {
                listeners.remove(listener);
            }
            if (listeners.isEmpty()) {
                listenerMap.remove(key);
                unregisterCacheListener(key);
            }
        }
    }

    private void registerCacheListener(String key) {
        if (curator == null) {
            return;
        }
        CuratorCache existingCache = cacheMap.get(key);
        if (existingCache == null) {
            String path = toConfigPath(key);
            CuratorCache cache = CuratorCache.build(curator, path);
            cache.listenable().addListener(new CuratorCacheListener() {
                @Override
                public void event(Type type, ChildData oldData, ChildData data) {
                    if (type == CuratorCacheListener.Type.NODE_CHANGED || type == CuratorCacheListener.Type.NODE_CREATED) {
                        String value = data != null && data.getData() != null
                                ? new String(data.getData(), StandardCharsets.UTF_8) : null;
                        List<ConfigurationListener> ls = listenerMap.get(key);
                        if (ls != null) {
                            for (ConfigurationListener l : ls) {
                                l.onConfigChanged(key, value);
                            }
                        }
                        log.info("[ZookeeperDynamicConfiguration] config changed: key={}, value={}", key, value);
                    } else if (type == CuratorCacheListener.Type.NODE_DELETED) {
                        List<ConfigurationListener> ls = listenerMap.get(key);
                        if (ls != null) {
                            for (ConfigurationListener l : ls) {
                                l.onConfigChanged(key, null);
                            }
                        }
                        log.info("[ZookeeperDynamicConfiguration] config deleted: key={}", key);
                    }
                }
            });
            CuratorCache prev = cacheMap.putIfAbsent(key, cache);
            if (prev == null) {
                cache.start();
            }
        }
    }

    private void unregisterCacheListener(String key) {
        CuratorCache cache = cacheMap.remove(key);
        if (cache != null) {
            try {
                cache.close();
            } catch (Exception e) {
                log.warn("[ZookeeperDynamicConfiguration] failed to close cache: key={}, msg={}", key, e.getMessage());
            }
        }
    }
}
