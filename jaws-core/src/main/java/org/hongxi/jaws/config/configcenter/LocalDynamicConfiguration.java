package org.hongxi.jaws.config.configcenter;

import org.hongxi.jaws.common.extension.Extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Local in-memory implementation of {@link DynamicConfiguration}.
 * <p>
 * This is the default implementation used when no remote config center is available.
 * All configurations are stored in a ConcurrentHashMap and lost on restart.
 * <p>
 * Created by shenhongxi on 2026/8/11.
 */
@Extension(name = "local")
public class LocalDynamicConfiguration implements DynamicConfiguration {

    private final ConcurrentMap<String, String> configs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ConfigurationListener>> listenerMap = new ConcurrentHashMap<>();

    @Override
    public boolean hasAnyConfig() {
        return !configs.isEmpty();
    }

    @Override
    public String getConfig(String key) {
        return configs.get(key);
    }

    @Override
    public void setConfig(String key, String value) {
        if (value == null) {
            configs.remove(key);
        } else {
            configs.put(key, value);
        }
        notifyListeners(key, value);
    }

    @Override
    public void addListener(String key, ConfigurationListener listener) {
        List<ConfigurationListener> listeners = Collections.synchronizedList(new ArrayList<>());
        List<ConfigurationListener> existing = listenerMap.putIfAbsent(key, listeners);
        Objects.requireNonNullElse(existing, listeners).add(listener);
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
        }
    }

    private void notifyListeners(String key, String newValue) {
        List<ConfigurationListener> listeners = listenerMap.get(key);
        if (listeners != null) {
            for (ConfigurationListener listener : listeners) {
                listener.onConfigChanged(key, newValue);
            }
        }
    }
}
