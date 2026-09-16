package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single authoritative client-connection closure transaction, the harbor
 * counterpart of Nacos's
 * {@code ConnectionBasedClientManager.clientDisconnected(String)}
 * (naming/core/v2/client/manager/impl/ConnectionBasedClientManager.java):
 * ONE place that performs every side effect of a client connection going away,
 * in the one correct order. Nacos reaches that same single entry from both its
 * disconnect listener and its {@code ExpiredClientCleaner} watchdog — which is
 * precisely the property this class encodes.
 * <p>
 * Three independent signals observe a closure — the bi-stream {@code onError}/
 * {@code onCompleted} (stream dies first), {@code channelInactive} from
 * {@link DisconnectionHandler} (the TCP connection closes), and the
 * {@link HealthCheckManager} watchdog sweep (a half-open TCP that never fires
 * {@code channelInactive}). Before this class existed, the first two had drifted
 * into two line-by-line copies and the watchdog ran only a partial subset,
 * leaking subscriber registrations and delaying peer convergence. All three
 * now call {@link #cleanup(String)}:
 * <ol>
 *   <li>capture the client's {@link ClientSyncData} snapshot — it requires the
 *       {@link ClientSession} to still exist, so it MUST run before eviction
 *       (see {@code WatchdogClosureTest#syncSnapshotIsCapturedBeforeSessionEviction});</li>
 *   <li>drop the connection's subscriber registrations;</li>
 *   <li>deregister the connection's published instances (notifying subscribers);</li>
 *   <li>evict the connection record and its {@link ClientSession}, completing
 *       the push subject;</li>
 *   <li>propagate a Distro DELETE to peers if (and only if) the client owned
 *       anything — peers converge immediately instead of waiting for the next
 *       verify cycle.</li>
 * </ol>
 * The transaction is idempotent: whichever signal evicts the session first wins,
 * and every later call observes {@code syncData == null} and no-ops, so the
 * common {@code onError → channelInactive} double-signal is safe.
 *
 * @author shenhongxi
 */
public class ConnectionCleanup {

    private static final Logger log = LoggerFactory.getLogger(ConnectionCleanup.class);

    private final ConnectionManager connectionManager;
    private final ServiceStorage serviceStorage;
    private final DistroProtocol distroProtocol;

    ConnectionCleanup(ConnectionManager connectionManager,
                      ServiceStorage serviceStorage,
                      DistroProtocol distroProtocol) {
        this.connectionManager = connectionManager;
        this.serviceStorage = serviceStorage;
        this.distroProtocol = distroProtocol;
    }

    /**
     * Run the full closure transaction for one connection. Safe to call from
     * any thread and any number of times; only the first effective call has
     * side effects. A {@code null}/blank id is ignored (connection never got
     * an id in the first place → nothing to clean).
     */
    void cleanup(String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) {
            return;
        }
        // (1) Snapshot BEFORE eviction — the source of truth for "did this
        // client own anything?" and for the DELETE decision below.
        ClientSyncData syncData = serviceStorage.buildClientSyncData(connectionId);

        // (2) Drop the connection's subscriber registrations — done while the
        // session is still alive to own them.
        serviceStorage.removeAllSubscribersForConnection(connectionId);

        // (3) Deregister the connection's published instances (notifying subscribers).
        int removed = serviceStorage.deregisterInstancesByConnectionId(connectionId);
        if (removed > 0) {
            log.info("[harbor] deregistered {} instance(s) on connection closure: connId={}",
                    removed, connectionId);
        }

        // (4) Evict the connection record + ClientSession (idempotent — removes
        // whatever is left; completes the push subject exactly once).
        connectionManager.remove(connectionId);

        // (5) Tell peers the client is gone — only if it owned service data.
        if (syncData != null && !syncData.getServiceKeys().isEmpty()) {
            distroProtocol.requestSyncDelete(connectionId);
        }
    }
}
