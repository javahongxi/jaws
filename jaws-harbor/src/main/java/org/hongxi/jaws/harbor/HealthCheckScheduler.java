package org.hongxi.jaws.harbor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic health check scheduler for ephemeral service instances and connections.
 * <p>
 * Modeled after Nacos 2.x connection-based health check:
 * <ul>
 *   <li>A scheduled task runs every {@link #CHECK_INTERVAL_MS} (default 5s)</li>
 *   <li><b>Connection watchdog</b>: connections whose last activity exceeds
 *       {@link #CONNECTION_TIMEOUT_MS} (default 90s) are considered dead —
 *       each one goes through the full closure transaction
 *       ({@link ConnectionCleanup#cleanup}), exactly the same side effects
 *       a live {@code channelInactive} would produce</li>
 *   <li><b>Instance health</b>: reconciled against connection liveness (Nacos 2.x
 *       model) — a connection idle past {@link #INSTANCE_UNHEALTHY_TIMEOUT_MS} marks
 *       its instances {@code unhealthy}; activity restores them</li>
 *   <li><b>Replica reclamation</b>: a synced (non-native) client that no peer has
 *       confirmed for {@link #SYNCED_SESSION_TIMEOUT_MS} is dropped entirely, since
 *       once the node owning its connection is gone nothing can announce its
 *       departure — and only its instances are visible to the tiers above</li>
 * </ul>
 * Connection activity is tracked via {@link ConnectionManager#refreshActiveTime(String)}
 * on every inbound unary/bi-stream request.
 * <p>
 * Those three are the health model. Each sweep runs one more, non-health step out of
 * convenience — empty-service auto-cleanup (Nacos {@code EmptyServiceAutoCleanerV2}),
 * to bound memory; it carries no health verdict and imposes no ordering on the tiers.
 *
 * @author shenhongxi
 */
public class HealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);

    /**
     * How often the health check task runs (milliseconds).
     * Matches Nacos {@code DEFAULT_HEART_BEAT_INTERVAL = 5s}.
     */
    private static final long CHECK_INTERVAL_MS = 5_000;

    /**
     * Connection inactivity timeout (milliseconds).
     * If no request arrives from a connection within this window,
     * the connection is considered dead and cleaned up.
     * 90s = 18× the default Nacos client heartbeat interval (5s).
     */
    private static final long CONNECTION_TIMEOUT_MS = 90_000;

    /**
     * First health tier (Nacos {@code HEART_BEAT_TIMEOUT}): a connection idle for
     * longer than this has its instances marked unhealthy (kept, but flagged) well
     * before the watchdog judges the connection dead at {@link #CONNECTION_TIMEOUT_MS}.
     * ~3× the 5s beat interval.
     */
    private static final long INSTANCE_UNHEALTHY_TIMEOUT_MS = 15_000;

    /**
     * How long a REPLICATED (synced) client may go unconfirmed by its owner before
     * this node drops it. Set to Nacos's {@code DEFAULT_CLIENT_EXPIRED_TIME} (3 min):
     * one full expiry window of silence means the owning node is gone rather than merely
     * idle — while it lives, its 30s refresh pass and matching verify revisions keep
     * every replica's confirmation clock moving.
     */
    private static final long SYNCED_SESSION_TIMEOUT_MS = 180_000;

    private final ConnectionManager connectionManager;
    private final ServiceStorage serviceStorage;
    private final ConnectionCleanup connectionCleanup;
    private final ScheduledExecutorService scheduler;

    public HealthCheckScheduler(ConnectionManager connectionManager,
                                ServiceStorage serviceStorage,
                                ConnectionCleanup connectionCleanup) {
        this.connectionManager = connectionManager;
        this.serviceStorage = serviceStorage;
        this.connectionCleanup = connectionCleanup;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harbor-health-check");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the periodic health check task.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::checkHealth,
                CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("[harbor] health check started, interval={}ms, connTimeout={}ms",
                CHECK_INTERVAL_MS, CONNECTION_TIMEOUT_MS);
    }

    /**
     * Shut down the health check scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    /**
     * One health-check sweep. Package-private so tests can drive a deterministic
     * pass without waiting on the {@link #CHECK_INTERVAL_MS} scheduler.
     */
    void checkHealth() {
        try {
            // Phase 1 — connection watchdog: detect dead connections, then run the SAME
            // full closure transaction as channelInactive / bi-stream signals
            // (snapshot → subscribers → instances → session → Distro DELETE).
            List<ConnectionManager.ConnectionRecord> staleConns =
                    connectionManager.removeStaleConnections(CONNECTION_TIMEOUT_MS);
            for (ConnectionManager.ConnectionRecord conn : staleConns) {
                connectionCleanup.cleanup(conn.connectionId());
                log.info("[harbor] watchdog closed dead connection: connId={}, clientIp={}",
                        conn.connectionId(), conn.clientIp());
            }

            // Phase 2 — instance health: reconcile instance health against connection
            // liveness (Nacos 2.x model). A connection idle past the unhealthy window
            // marks its instances unhealthy; activity restores them. Connection death is
            // the watchdog's job (Phase 1), so there is no separate per-instance expiry tier.
            serviceStorage.reconcileHealth(INSTANCE_UNHEALTHY_TIMEOUT_MS);

            // Phase 3 — replica reclamation: reclaim replicated clients their owner no
            // longer confirms. The two phases above reach only native connections'
            // instances, so a subscriber-only replica — and any session shell left
            // behind — would otherwise survive for the whole process lifetime once the
            // node owning the connection is gone (nothing can announce that client
            // again: no beat, no Distro DELETE, and the watchdog cannot see a synced session).
            int reaped = serviceStorage.reapStaleSyncedClients(SYNCED_SESSION_TIMEOUT_MS);
            if (reaped > 0) {
                log.info("[harbor] reclaimed {} replicated client session(s) unconfirmed "
                        + "by their owner for {}ms", reaped, SYNCED_SESSION_TIMEOUT_MS);
            }

            // Housekeeping — not a health phase: drop services with no publishers and no
            // subscribers to bound memory. Order-independent, it just rides the same tick
            // (matches Nacos EmptyServiceAutoCleanerV2).
            serviceStorage.cleanEmptyServices();
        } catch (Exception e) {
            log.warn("[harbor] health check task failed", e);
        }
    }
}
