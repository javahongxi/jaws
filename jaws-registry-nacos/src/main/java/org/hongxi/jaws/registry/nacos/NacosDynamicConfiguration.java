package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.config.configcenter.ConfigurationListener;
import org.hongxi.jaws.config.configcenter.DynamicConfiguration;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * Nacos-based implementation of {@link DynamicConfiguration}.
 * <p>
 * Uses Nacos ConfigService to store and retrieve configuration values.
 * Supports remote config push via Nacos listener mechanism.
 * <p>
 * Configuration mapping:
 * <ul>
 *   <li>key → Nacos dataId</li>
 *   <li>group → Nacos group (default: "JAWS_CONFIG")</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/8/11.
 */
@Extension("nacos")
public class NacosDynamicConfiguration implements DynamicConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NacosDynamicConfiguration.class);

    private static final String DEFAULT_GROUP = "JAWS_CONFIG";
    private static final long DEFAULT_TIMEOUT = 5000L;

    private volatile ConfigService configService;
    private final ConcurrentMap<String, String> localCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ConfigurationListener>> listenerMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Listener> nacosListenerMap = new ConcurrentHashMap<>();

    /**
     * Initialize with a Nacos registry URL. Extracts connection info to create ConfigService.
     */
    @Override
    public void init(URL registryUrl) {
        try {
            Properties properties = new Properties();
            properties.put(PropertyKeyConst.SERVER_ADDR,
                    registryUrl.getHost() + ":" + registryUrl.getPort());
            String username = registryUrl.getParameter("username");
            String password = registryUrl.getParameter("password");
            if (username != null) {
                properties.put(PropertyKeyConst.USERNAME, username);
            }
            if (password != null) {
                properties.put(PropertyKeyConst.PASSWORD, password);
            }
            String namespace = registryUrl.getParameter("namespace");
            if (namespace != null) {
                properties.put(PropertyKeyConst.NAMESPACE, namespace);
            }
            this.configService = NacosFactory.createConfigService(properties);
            log.info("initialized with server={}:{}", registryUrl.getHost(), registryUrl.getPort());
        } catch (NacosException e) {
            throw new RuntimeException("Failed to create Nacos ConfigService", e);
        }
    }

    @Override
    public boolean hasAnyConfig() {
        return !localCache.isEmpty();
    }

    @Override
    public String getConfig(String key) {
        String value = localCache.get(key);
        if (value != null) {
            return value;
        }
        if (configService == null) {
            log.warn("configService not initialized, returning null for key={}", key);
            return null;
        }
        try {
            value = configService.getConfig(key, DEFAULT_GROUP, DEFAULT_TIMEOUT);
            if (value != null) {
                localCache.put(key, value);
                ensureNacosListener(key);
            }
            return value;
        } catch (NacosException e) {
            log.warn("failed to get config: key={}", key, e);
            return null;
        }
    }

    @Override
    public void setConfig(String key, String value) {
        if (configService == null) {
            log.warn("configService not initialized, cannot set key={}", key);
            return;
        }
        try {
            if (value == null) {
                configService.removeConfig(key, DEFAULT_GROUP);
                localCache.remove(key);
            } else {
                configService.publishConfig(key, DEFAULT_GROUP, value);
                localCache.put(key, value);
                ensureNacosListener(key);
            }
        } catch (NacosException e) {
            log.warn("failed to set config: key={}", key, e);
        }
    }

    @Override
    public void addListener(String key, ConfigurationListener listener) {
        List<ConfigurationListener> listeners = Collections.synchronizedList(new ArrayList<>());
        List<ConfigurationListener> existing = listenerMap.putIfAbsent(key, listeners);
        if (existing == null) {
            listeners.add(listener);
            ensureNacosListener(key);
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
            // If no more listeners, remove Nacos listener
            if (listeners.isEmpty()) {
                listenerMap.remove(key);
                unregisterNacosListener(key);
            }
        }
    }

    /**
     * Ensure a Nacos listener is registered for the given key to keep localCache in sync.
     */
    private void ensureNacosListener(String key) {
        if (configService == null || nacosListenerMap.containsKey(key)) {
            return;
        }
        Listener nacosListener = new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                updateCacheFromRemote(key, configInfo);
            }
        };
        Listener prev = nacosListenerMap.putIfAbsent(key, nacosListener);
        if (prev == null) {
            try {
                configService.addListener(key, DEFAULT_GROUP, nacosListener);
            } catch (NacosException e) {
                log.warn("failed to add listener: key={}", key, e);
            }
        }
    }

    private void updateCacheFromRemote(String key, String value) {
        if (value != null) {
            localCache.put(key, value);
        } else {
            localCache.remove(key);
        }
        List<ConfigurationListener> listeners = listenerMap.get(key);
        if (listeners != null) {
            for (ConfigurationListener l : listeners) {
                l.onConfigChanged(key, value);
            }
        }
        log.info("config changed: key={}, value={}", key, value);
    }

    private void unregisterNacosListener(String key) {
        Listener nacosListener = nacosListenerMap.remove(key);
        if (nacosListener != null && configService != null) {
            try {
                configService.removeListener(key, DEFAULT_GROUP, nacosListener);
            } catch (Exception e) {
                log.warn("failed to remove listener: key={}", key, e);
            }
        }
    }
}
