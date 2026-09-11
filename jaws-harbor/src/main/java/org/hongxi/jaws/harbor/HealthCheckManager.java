package org.hongxi.jaws.harbor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic health check manager for ephemeral service instances.
 * <p>
 * Modeled after Nacos 2.x connection-based health check:
 * <ul>
 *   <li>A scheduled task runs every {@link #CHECK_INTERVAL_MS} (default 5s)</li>
 *   <li>Any instance whose last heartbeat exceeds {@link #INSTANCE_TIMEOUT_MS}
 *       (default 3 min) is considered dead and removed from storage</li>
 *   <li>Subscribers of the affected service are notified on removal</li>
 * </ul>
 * The heartbeat timestamp is updated whenever the instance sends a request
 * (register, subscribe, health check, etc.) via
 * {@link ServiceStorage#updateInstanceHeartbeat}.
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
     * Instance heartbeat timeout (milliseconds).
     * Matches Nacos {@code DEFAULT_CLIENT_EXPIRED_TIME = 3min}.
     */
    private static final long INSTANCE_TIMEOUT_MS = 180_000;

    private final ServiceStorage serviceStorage;
    private final ScheduledExecutorService scheduler;

    public HealthCheckManager(ServiceStorage serviceStorage) {
        this.serviceStorage = serviceStorage;
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
        log.info("[harbor] health check started, interval={}ms, timeout={}ms",
                CHECK_INTERVAL_MS, INSTANCE_TIMEOUT_MS);
    }

    /**
     * Shut down the health check scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    private void checkHealth() {
        try {
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
