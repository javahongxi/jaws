package org.hongxi.jaws.harbor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic health check manager for ephemeral service instances and connections.
 * <p>
 * Modeled after Nacos 2.x connection-based health check:
 * <ul>
 *   <li>A scheduled task runs every {@link #CHECK_INTERVAL_MS} (default 5s)</li>
 *   <li><b>Connection watchdog</b>: connections whose last activity exceeds
 *       {@link #CONNECTION_TIMEOUT_MS} (default 90s) are considered dead —
 *       the bi-stream is completed and all instances from that client IP
 *       are deregistered immediately</li>
 *   <li><b>Instance heartbeat</b>: any instance whose last heartbeat exceeds
 *       {@link #INSTANCE_TIMEOUT_MS} (default 3 min) is removed as a fallback
 *       for edge cases (e.g. half-open TCP that hasn't triggered watchdog yet)</li>
 * </ul>
 * Connection activity is tracked via {@link ConnectionManager#touch(String)}
 * on every inbound unary/bi-stream request.
 *
 * @author shenhongxi
 */
public class HealthCheckManager {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckManager.class);

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
     * Instance heartbeat timeout (milliseconds).
     * Matches Nacos {@code DEFAULT_CLIENT_EXPIRED_TIME = 3min}.
     * Serves as a fallback for edge cases where the connection watchdog
     * hasn't fired yet (e.g. half-open TCP).
     */
    private static final long INSTANCE_TIMEOUT_MS = 180_000;

    private final ServiceStorage serviceStorage;
    private final ConnectionManager connectionManager;
    private final ScheduledExecutorService scheduler;

    public HealthCheckManager(ServiceStorage serviceStorage, ConnectionManager connectionManager) {
        this.serviceStorage = serviceStorage;
        this.connectionManager = connectionManager;
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
        log.info("[harbor] health check started, interval={}ms, connTimeout={}ms, instanceTimeout={}ms",
                CHECK_INTERVAL_MS, CONNECTION_TIMEOUT_MS, INSTANCE_TIMEOUT_MS);
    }

    /**
     * Shut down the health check scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    private void checkHealth() {
        try {
            // Phase 1: connection watchdog — close dead connections first
            List<ConnectionManager.ConnectionRecord> staleConns =
                    connectionManager.removeStaleConnections(CONNECTION_TIMEOUT_MS);
            for (ConnectionManager.ConnectionRecord conn : staleConns) {
                String connId = conn.connectionId();
                int removed = serviceStorage.deregisterInstancesByConnectionId(connId);
                if (removed > 0) {
                    log.info("[harbor] watchdog deregistered {} instance(s) for dead connection: "
                            + "connId={}, clientIp={}", removed, connId, conn.clientIp());
                }
            }

            // Phase 2: instance heartbeat fallback — clean up any remaining expired instances
            List<ServiceStorage.ExpiredInstance> expired =
                    serviceStorage.getExpiredInstances(INSTANCE_TIMEOUT_MS);
            if (!expired.isEmpty()) {
                log.info("[harbor] health check found {} expired instance(s)", expired.size());
                for (ServiceStorage.ExpiredInstance inst : expired) {
                    serviceStorage.removeInstanceByIpPort(inst.serviceKey(), inst.ip(), inst.port());
                }
            }
        } catch (Exception e) {
            log.warn("[harbor] health check task failed", e);
        }
    }
}
