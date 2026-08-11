package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.hongxi.jaws.common.extension.SpiMeta;
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
@SpiMeta(name = "nacos")
public class NacosDynamicConfiguration implements DynamicConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NacosDynamicConfiguration.class);

    private static final String DEFAULT_GROUP = "JAWS_CONFIG";
    private static final long DEFAULT_TIMEOUT = 5000L;

    private volatile ConfigService configService;
    private final ConcurrentMap<String, List<ConfigurationListener>> listenerMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Listener> nacosListenerMap = new ConcurrentHashMap<>();

    /**
     * Initialize with a Nacos registry URL. Extracts connection info to create ConfigService.
     */
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
            log.info("[NacosDynamicConfiguration] initialized with server={}:{}", registryUrl.getHost(), registryUrl.getPort());
        } catch (NacosException e) {
            throw new RuntimeException("Failed to create Nacos ConfigService", e);
        }
    }

    /**
     * Initialize with an existing ConfigService (shared with NacosRegistry).
     */
    public void init(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String getConfig(String key) {
        if (configService == null) {
            log.warn("[NacosDynamicConfiguration] configService not initialized, returning null for key={}", key);
            return null;
        }
        try {
            return configService.getConfig(key, DEFAULT_GROUP, DEFAULT_TIMEOUT);
        } catch (NacosException e) {
            log.warn("[NacosDynamicConfiguration] failed to get config: key={}, msg={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void setConfig(String key, String value) {
        if (configService == null) {
            log.warn("[NacosDynamicConfiguration] configService not initialized, cannot set key={}", key);
            return;
        }
        try {
            if (value == null) {
                configService.removeConfig(key, DEFAULT_GROUP);
            } else {
                configService.publishConfig(key, DEFAULT_GROUP, value);
            }
        } catch (NacosException e) {
            log.warn("[NacosDynamicConfiguration] failed to set config: key={}, msg={}", key, e.getMessage());
        }
    }

    @Override
    public void addListener(String key, ConfigurationListener listener) {
        List<ConfigurationListener> listeners = Collections.synchronizedList(new ArrayList<>());
        List<ConfigurationListener> existing = listenerMap.putIfAbsent(key, listeners);
        if (existing == null) {
            listeners.add(listener);
            // Register Nacos listener if not already registered
            registerNacosListener(key);
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

    private void registerNacosListener(String key) {
        if (configService == null) {
            return;
        }
        Listener nacosListener = nacosListenerMap.get(key);
        if (nacosListener == null) {
            nacosListener = new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    List<ConfigurationListener> listeners = listenerMap.get(key);
                    if (listeners != null) {
                        for (ConfigurationListener l : listeners) {
                            l.onConfigChanged(key, configInfo);
                        }
                    }
                    log.info("[NacosDynamicConfiguration] config changed: key={}, value={}", key, configInfo);
                }
            };
            Listener prev = nacosListenerMap.putIfAbsent(key, nacosListener);
            if (prev == null) {
                try {
                    configService.addListener(key, DEFAULT_GROUP, nacosListener);
                } catch (NacosException e) {
                    log.warn("[NacosDynamicConfiguration] failed to add listener: key={}, msg={}", key, e.getMessage());
                }
            }
        }
    }

    private void unregisterNacosListener(String key) {
        Listener nacosListener = nacosListenerMap.remove(key);
        if (nacosListener != null && configService != null) {
            try {
                configService.removeListener(key, DEFAULT_GROUP, nacosListener);
            } catch (Exception e) {
                log.warn("[NacosDynamicConfiguration] failed to remove listener: key={}, msg={}", key, e.getMessage());
            }
        }
    }
}
