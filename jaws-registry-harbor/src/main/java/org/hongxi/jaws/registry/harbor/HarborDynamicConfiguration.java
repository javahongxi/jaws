package org.hongxi.jaws.registry.harbor;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.configcenter.ConfigurationListener;
import org.hongxi.jaws.configcenter.DynamicConfiguration;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Harbor-based implementation of {@link DynamicConfiguration}, the same shape
 * as {@code NacosDynamicConfiguration}: config rides its OWN connection, apart
 * from the registry's naming connection, and each key maps to its own dataId.
 * <p>
 * Configuration mapping (identical to the nacos leg, so a key written through
 * either leg reads back from the other):
 * <ul>
 *   <li>key → harbor dataId</li>
 *   <li>group → {@link HarborProtocol#JAWS_CONFIG_GROUP}</li>
 * </ul>
 * Writes commit through the server's raft log; pushes arrive on this client's
 * config listen and fan out to the framework's per-key listeners.
 *
 * @author shenhongxi
 */
@Extension(JawsConstants.REGISTRY_PROTOCOL_HARBOR)
public class HarborDynamicConfiguration implements DynamicConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HarborDynamicConfiguration.class);

    private volatile HarborClient configClient;
    private final ConcurrentMap<String, String> localCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ConfigurationListener>> listenerMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Consumer<String>> harborListenerMap = new ConcurrentHashMap<>();

    /**
     * Reentrancy guard for remote reads. The harbor SDK itself rides the jaws
     * wire stack, whose timeout resolution reads dynamic config — a remote
     * getConfig would otherwise recurse into itself until the stack overflows.
     * A nested read answers from the local cache only, breaking the cycle.
     */
    private static final ThreadLocal<Boolean> READING = ThreadLocal.withInitial(() -> false);

    /**
     * Initialize with a harbor registry URL. The config client is a separate
     * {@link HarborClient} over the same address list — naming and config each
     * keep their own connection, exactly as the nacos leg keeps NamingService
     * and ConfigService apart.
     */
    @Override
    public void init(URL registryUrl) {
        String address = registryUrl.getBackupAddress();
        this.configClient = new HarborClient(HarborClientConfig.ofCluster(address));
        log.info("initialized with server={}", address);
    }

    @Override
    public String getConfig(String key) {
        String value = localCache.get(key);
        if (value != null) {
            return value;
        }
        if (configClient == null) {
            log.warn("configClient not initialized, returning null for key={}", key);
            return null;
        }
        if (READING.get()) {
            // Nested read from the SDK's own timeout resolution: cache only.
            return null;
        }
        READING.set(true);
        try {
            value = configClient.getConfig(key, HarborProtocol.JAWS_CONFIG_GROUP);
            if (value != null) {
                localCache.put(key, value);
                ensureHarborListener(key);
            }
            return value;
        } catch (Exception e) {
            log.warn("failed to get config: key={}", key, e);
            return null;
        } finally {
            READING.set(false);
        }
    }

    @Override
    public void setConfig(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null; use removeConfig(key) to delete");
        }
        if (configClient == null) {
            log.warn("configClient not initialized, cannot set key={}", key);
            return;
        }
        try {
            configClient.publishConfig(key, HarborProtocol.JAWS_CONFIG_GROUP, value);
            localCache.put(key, value);
            ensureHarborListener(key);
        } catch (Exception e) {
            log.warn("failed to set config: key={}", key, e);
        }
    }

    @Override
    public boolean removeConfig(String key) {
        if (configClient == null) {
            log.warn("configClient not initialized, cannot remove key={}", key);
            return false;
        }
        try {
            configClient.removeConfig(key, HarborProtocol.JAWS_CONFIG_GROUP);
            localCache.remove(key);
            // The registered harbor listener fires with null content on its own,
            // matching how publish-driven changes propagate; no manual notify here.
            return true;
        } catch (Exception e) {
            log.warn("failed to remove config: key={}", key, e);
            return false;
        }
    }

    @Override
    public void addListener(String key, ConfigurationListener listener) {
        // CopyOnWriteArrayList: listeners are iterated lock-free in updateCacheFromRemote
        // while addListener/removeListener may mutate concurrently
        List<ConfigurationListener> listeners = new CopyOnWriteArrayList<>();
        List<ConfigurationListener> existing = listenerMap.putIfAbsent(key, listeners);
        if (existing == null) {
            listeners.add(listener);
            ensureHarborListener(key);
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
            // If no more listeners, remove the harbor listen
            if (listeners.isEmpty()) {
                listenerMap.remove(key);
                unregisterHarborListener(key);
            }
        }
    }

    /**
     * Ensure a harbor config listen is registered for the given key to keep
     * localCache in sync. The native client hands over the current value at
     * once, so a late subscriber still learns what is already committed.
     */
    private void ensureHarborListener(String key) {
        if (configClient == null || harborListenerMap.containsKey(key)) {
            return;
        }
        Consumer<String> harborListener = content -> updateCacheFromRemote(key, content);
        Consumer<String> prev = harborListenerMap.putIfAbsent(key, harborListener);
        if (prev == null) {
            try {
                configClient.addConfigListener(key, HarborProtocol.JAWS_CONFIG_GROUP, harborListener);
            } catch (Exception e) {
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

    private void unregisterHarborListener(String key) {
        Consumer<String> harborListener = harborListenerMap.remove(key);
        if (harborListener != null && configClient != null) {
            try {
                configClient.removeConfigListener(key, HarborProtocol.JAWS_CONFIG_GROUP, harborListener);
            } catch (Exception e) {
                log.warn("failed to remove listener: key={}", key, e);
            }
        }
    }
}