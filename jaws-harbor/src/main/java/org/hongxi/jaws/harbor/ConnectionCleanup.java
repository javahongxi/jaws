package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.transport.StreamSubject;
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
 * {@link HealthCheckScheduler} watchdog sweep (a half-open TCP that never fires
 * {@code channelInactive}). Before this class existed, the first two had drifted
 * into two line-by-line copies and the watchdog ran only a partial subset,
 * leaking subscriber registrations and delaying peer convergence. All three
 * now call {@link #cleanup(String)}:
 * <ol>
 *   <li>capture the client's {@link ClientSyncData} snapshot — it requires the
 *       {@link ClientSession} to still exist, so it MUST run before eviction
 *       (see {@code WatchdogClosureTest#syncSnapshotIsCapturedBeforeSessionEviction});</li>
 *   <li>drop the connection's subscriber registrations;</li>
 *   <li>drop the connection's config listens (node-local, no peer traffic —
 *       the replayed listen round re-arms them on the new connection);</li>
 *   <li>deregister the connection's published instances (notifying subscribers);</li>
 *   <li>evict the connection record and its {@link ClientSession}, completing
 *       the push subject;</li>
 *   <li>propagate a Distro DELETE to peers if the client owned anything OR a
 *       coalesced push is still queued for it — a graceful deregister empties
 *       the ownership snapshot while the peer may still hold the last CHANGE,
 *       and the queued push dies with the evicted session; peers converge
 *       immediately instead of stranding the replica.</li>
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
    private final ConfigListenContext configListenContext;

    ConnectionCleanup(ConnectionManager connectionManager,
                      ServiceStorage serviceStorage,
                      DistroProtocol distroProtocol,
                      ConfigListenContext configListenContext) {
        this.connectionManager = connectionManager;
        this.serviceStorage = serviceStorage;
        this.distroProtocol = distroProtocol;
        this.configListenContext = configListenContext;
    }

    /**
     * Closure transaction for a notification stream that ended, guarded by stream
     * identity: when the connection has already been re-established on the same TCP
     * channel, this stream is history and nothing may be torn down. Everything the
     * stale session owned is either re-registered by the client's replay or belongs
     * to the live session with the same id, so skipping is the safe half.
     */
    boolean cleanup(String connectionId, StreamSubject<Message> ownedPushSubject) {
        ConnectionManager.ConnectionRecord live = connectionManager.allConnections().stream()
                .filter(each -> each.connectionId().equals(connectionId))
                .findFirst().orElse(null);
        if (live != null && live.pushSubject() != ownedPushSubject) {
            log.info("[harbor] stale notification stream ended on a re-established "
                    + "connection, closure skipped: connId={}", connectionId);
            return false;
        }
        cleanup(connectionId);
        return true;
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

        // (2b) Drop the connection's config listens. These are node-local push
        // routes, not replicated data — after a reconnect the client's listen
        // round re-registers them, so no peer sync is needed here.
        configListenContext.removeConnection(connectionId);

        // (3) Deregister the connection's published instances (notifying subscribers).
        int removed = serviceStorage.deregisterInstancesByConnectionId(connectionId);
        if (removed > 0) {
            log.info("[harbor] deregistered {} instance(s) on connection closure: connId={}",
                    removed, connectionId);
        }

        // (4) Evict the connection record + ClientSession (idempotent — removes
        // whatever is left; completes the push subject exactly once).
        connectionManager.remove(connectionId);

        // (5) Tell peers the client is gone. Current ownership alone cannot
        // witness this: a graceful deregister empties the snapshot while a
        // coalesced push is still queued, and that push dies with the session
        // (buildClientSyncData returns null once evicted) — stranding the
        // peer's last CHANGE with no later repair (verify no longer sees the
        // client here). A queued ticket therefore counts as ownership too;
        // requestSyncDelete cancels it and converges the peers. A settled
        // deregister (ticket already fired its DELETE) and a client that never
        // published both leave nothing queued and emit nothing.
        if (syncData != null
                && (!syncData.getServiceKeys().isEmpty() || distroProtocol.hasPendingSync(connectionId))) {
            distroProtocol.requestSyncDelete(connectionId);
        }
    }
}
