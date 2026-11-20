package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.request.ConfigChangeNotifyRequest;
import org.hongxi.jaws.harbor.proto.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Config change push — the harbor counterpart of nacos's
 * {@code RpcConfigChangeNotifier}. A committed config change enqueues one
 * coalesced task per key; when it fires it re-reads the CURRENT listener set
 * and pushes a {@link ConfigChangeNotifyRequest} to each connection attached
 * to this node. The notice carries only the key: the client queries the new
 * content, so a dropped push is healed by the next listen round.
 * <p>
 * Every node applies the same raft entry and notifies its own listeners, so
 * no inter-node relay exists — replication already delivered the change to
 * every node's state machine.
 *
 * @author shenhongxi
 */
public class ConfigChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeNotifier.class);

    /** Coalescing window: bursts on one key collapse into a single notice. */
    private static final long MERGE_DELAY_MS = 100L;

    private final ConfigListenContext listenContext;
    private final ConnectionManager connectionManager;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "harbor-config-notifier");
                t.setDaemon(true);
                return t;
            });

    /** Keys with a scheduled but not-yet-fired notice; presence = coalescing lock. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public ConfigChangeNotifier(ConfigListenContext listenContext,
                                ConnectionManager connectionManager) {
        this.listenContext = listenContext;
        this.connectionManager = connectionManager;
    }

    /**
     * Register that one config changed (or was removed). Called from the raft
     * apply thread on every node; the push itself runs off that thread.
     */
    public void requestNotify(String dataId, String group, String tenant) {
        if (scheduler.isShutdown()) {
            return;
        }
        String groupKey = ConfigStorage.groupKey(dataId, group, tenant);
        // add() returns true only on first insertion — coalescing guarantee.
        if (pending.add(groupKey)) {
            try {
                scheduler.schedule(() -> doNotify(groupKey, dataId, group, tenant),
                        MERGE_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                pending.remove(groupKey);
            }
        }
    }

    private void doNotify(String groupKey, String dataId, String group, String tenant) {
        pending.remove(groupKey);
        Map<String, ConfigListenContext.ConfigListener> watchers = listenContext.listenersOf(groupKey);
        if (watchers.isEmpty()) {
            return;
        }
        // A listener that sent a blank tenant keys its cache without one, so its
        // notice must carry a null tenant (nacos's namespace-transfer rule).
        Payload named = HarborProtocol.encodePush(
                ConfigChangeNotifyRequest.build(dataId, group, tenant));
        Payload transferred = HarborProtocol.encodePush(
                ConfigChangeNotifyRequest.build(dataId, group, null));
        int pushed = 0;
        for (Map.Entry<String, ConfigListenContext.ConfigListener> each : watchers.entrySet()) {
            Payload frame = each.getValue().namespaceTransfer() ? transferred : named;
            // A gone connection re-listens on reconnect; skip, do not retry.
            if (connectionManager.pushToConnection(each.getKey(), frame)) {
                pushed++;
            }
        }
        log.info("[harbor] config change notified key={} to {} listener(s)", groupKey, pushed);
    }

    public void shutdown() {
        pending.clear();
        scheduler.shutdownNow();
    }
}