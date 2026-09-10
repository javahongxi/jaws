package org.hongxi.jaws.harbor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory configuration storage for the config center.
 * <p>
 * Stores configs keyed by {@code namespace@@dataId@@group} and manages
 * per-config listener sets. When a config changes, all listeners are
 * notified via the {@link ConfigChangeListener} callback (which pushes
 * {@code ConfigChangeNotifyRequest} through the BiStream).
 *
 * @author shenhongxi
 */
public class ConfigStorage {

    private static final Logger log = LoggerFactory.getLogger(ConfigStorage.class);

    /**
     * Key: "namespace@@dataId@@group"
     * Value: config record (content, md5, lastModified, type)
     */
    private final Map<String, ConfigRecord> configMap = new ConcurrentHashMap<>();

    /**
     * Key: "namespace@@dataId@@group"
     * Value: set of listener connection IDs
     */
    private final Map<String, Set<String>> listenerMap = new ConcurrentHashMap<>();

    private final ConfigChangeListener listener;

    public ConfigStorage(ConfigChangeListener listener) {
        this.listener = listener;
    }

    /**
     * Publish (create or update) a config.
     */
    public boolean publishConfig(String namespace, String dataId, String group,
                                  String content, String type) {
        String key = buildKey(namespace, dataId, group);
        String md5 = md5(content);
        long now = System.currentTimeMillis();
        configMap.put(key, new ConfigRecord(dataId, group, namespace, content, md5, now,
                type != null ? type : "text"));
        log.info("[harbor] config published: {} (md5={})", key, md5);
        notifyListeners(key, namespace, dataId, group);
        return true;
    }

    /**
     * Query a config by dataId/group/namespace.
     *
     * @return the config record, or null if not found
     */
    public ConfigRecord queryConfig(String namespace, String dataId, String group) {
        return configMap.get(buildKey(namespace, dataId, group));
    }

    /**
     * Remove a config.
     */
    public boolean removeConfig(String namespace, String dataId, String group) {
        String key = buildKey(namespace, dataId, group);
        ConfigRecord removed = configMap.remove(key);
        if (removed != null) {
            log.info("[harbor] config removed: {}", key);
            notifyListeners(key, namespace, dataId, group);
            return true;
        }
        return false;
    }

    /**
     * Add a config listener (watch) for the given connection.
     */
    public void addListener(String namespace, String dataId, String group,
                            String connectionId) {
        String key = buildKey(namespace, dataId, group);
        listenerMap.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>())
                .add(connectionId);
        log.debug("[harbor] config listener added: conn={} for {}", connectionId, key);
    }

    /**
     * Remove a config listener.
     */
    public void removeListener(String namespace, String dataId, String group,
                               String connectionId) {
        String key = buildKey(namespace, dataId, group);
        Set<String> listeners = listenerMap.get(key);
        if (listeners != null) {
            listeners.remove(connectionId);
            if (listeners.isEmpty()) {
                listenerMap.remove(key);
            }
        }
    }

    /**
     * Remove all listener entries for a given connection (on disconnect).
     */
    public void removeAllListenersForConnection(String connectionId) {
        for (Map.Entry<String, Set<String>> entry : listenerMap.entrySet()) {
            entry.getValue().remove(connectionId);
            if (entry.getValue().isEmpty()) {
                listenerMap.remove(entry.getKey());
            }
        }
    }

    /**
     * List all config keys (for distro snapshot/verify).
     */
    public Map<String, ConfigRecord> getAllConfigs() {
        return Map.copyOf(configMap);
    }

    /**
     * Apply a snapshot from a peer node (distro load).
     */
    public void applySnapshot(Map<String, ConfigRecord> snapshot) {
        for (Map.Entry<String, ConfigRecord> entry : snapshot.entrySet()) {
            configMap.putIfAbsent(entry.getKey(), entry.getValue());
        }
        log.info("[harbor] config snapshot applied, size={}", configMap.size());
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private void notifyListeners(String key, String namespace, String dataId, String group) {
        Set<String> listeners = listenerMap.get(key);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (String connId : listeners) {
            listener.onConfigChange(connId, namespace, dataId, group);
        }
    }

    private static String buildKey(String namespace, String dataId, String group) {
        return namespace + "@@" + dataId + "@@" + group;
    }

    private static String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ========================================================================
    // Data types
    // ========================================================================

    /**
     * A single config record.
     */
    public record ConfigRecord(
            String dataId,
            String group,
            String namespace,
            String content,
            String md5,
            long lastModified,
            String type
    ) {}

    /**
     * Callback for notifying config listeners of changes.
     */
    public interface ConfigChangeListener {
        void onConfigChange(String connectionId, String namespace, String dataId, String group);
    }
}
