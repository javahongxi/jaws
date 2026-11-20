package org.hongxi.jaws.harbor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who watches which config, with the md5 each watcher holds — the harbor
 * counterpart of nacos's {@code ConfigChangeListenContext}. Listens are
 * per-connection state (never replicated): a reconnecting client re-listens
 * with its cached md5, and a closed connection's watches are dropped by the
 * closure transaction.
 * <p>
 * Each listen also records whether its tenant was transferred (a blank tenant
 * normalized to {@code public}): nacos-client keys its local cache by the
 * tenant it SENT, so a push to a transferred listener must carry a null tenant
 * or the client's lookup misses — the nacos {@code RpcConfigChangeNotifier}
 * namespace-transfer rule.
 *
 * @author shenhongxi
 */
public class ConfigListenContext {

    /** groupKey -> (connectionId -> what that connection's listen declared). */
    private final Map<String, Map<String, ConfigListener>> listeners = new ConcurrentHashMap<>();

    public void addListen(String groupKey, String md5, String connectionId,
                          boolean namespaceTransfer) {
        listeners.computeIfAbsent(groupKey, k -> new ConcurrentHashMap<>())
                .put(connectionId, new ConfigListener(md5 == null ? "" : md5, namespaceTransfer));
    }

    public void removeListen(String groupKey, String connectionId) {
        Map<String, ConfigListener> byConnection = listeners.get(groupKey);
        if (byConnection == null) {
            return;
        }
        byConnection.remove(connectionId);
        if (byConnection.isEmpty()) {
            listeners.remove(groupKey);
        }
    }

    /** Drop every watch a closed connection held. */
    public void removeConnection(String connectionId) {
        for (Map.Entry<String, Map<String, ConfigListener>> entry : listeners.entrySet()) {
            entry.getValue().remove(connectionId);
            if (entry.getValue().isEmpty()) {
                listeners.remove(entry.getKey());
            }
        }
    }

    /** The connections watching one key right now (a copy, safe to iterate). */
    public Map<String, ConfigListener> listenersOf(String groupKey) {
        Map<String, ConfigListener> byConnection = listeners.get(groupKey);
        return byConnection == null ? Map.of() : Map.copyOf(byConnection);
    }

    /** One connection's declared md5 and whether its blank tenant was normalized. */
    public record ConfigListener(String md5, boolean namespaceTransfer) {
    }
}