package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.proto.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages push notification retries for subscriber changes.
 * <p>
 * When a push to a subscriber fails (connection lost, stream error, etc.),
 * the notification is scheduled for retry with exponential backoff.
 * Matches the Nacos {@code PushDelayTaskExecuteEngine} concept.
 * <p>
 * Retry schedule: 1s → 2s → 4s → 8s → 16s (max 5 attempts).
 *
 * @author shenhongxi
 */
public class PushRetryManager {

    private static final Logger log = LoggerFactory.getLogger(PushRetryManager.class);

    private static final int MAX_RETRIES = 5;
    private static final long BASE_DELAY_MS = 1000;

    private final ConnectionManager connectionManager;
    private final ScheduledExecutorService retryScheduler;

    /**
     * Tracks pending retries to prevent unbounded accumulation.
     * Key: connectionId + "|" + serviceKey
     */
    private final Map<String, Boolean> pendingRetries = new ConcurrentHashMap<>();

    public PushRetryManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harbor-push-retry");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule a retry for a failed push notification.
     *
     * @param connectionId target connection
     * @param namespace    service namespace
     * @param group        service group
     * @param serviceName  service name
     * @param serviceInfo  the service data to push
     * @param attempt      current retry attempt (1-based)
     */
    public void scheduleRetry(String connectionId, String namespace, String group,
                               String serviceName, ServiceInfo serviceInfo, int attempt) {
        if (attempt > MAX_RETRIES) {
            log.warn("[harbor] push retry exhausted after {} attempts: connId={}, service={}@@{}@@{}",
                    MAX_RETRIES, connectionId, namespace, group, serviceName);
            return;
        }

        String serviceKey = namespace + "@@" + group + "@@" + serviceName;
        String retryKey = connectionId + "|" + serviceKey;

        // Prevent duplicate retries for the same connection + service
        if (pendingRetries.putIfAbsent(retryKey, Boolean.TRUE) != null) {
            return;
        }

        long delay = BASE_DELAY_MS * (1L << (attempt - 1));
        retryScheduler.schedule(() -> {
            try {
                // Check if the connection still exists before retrying
                ConnectionManager.ConnectionRecord record = findConnection(connectionId);
                if (record != null) {
                    NotifySubscriberRequest push = new NotifySubscriberRequest();
                    push.setNamespace(namespace);
                    push.setServiceName(serviceName);
                    push.setGroupName(group);
                    push.setServiceInfo(serviceInfo);

                    Payload pushPayload = HarborServer.buildPushPayload("NotifySubscriberRequest", push);
                    boolean pushed = connectionManager.pushToConnection(connectionId, pushPayload);
                    if (pushed) {
                        log.debug("[harbor] push retry succeeded: attempt={}, connId={}, service={}",
                                attempt, connectionId, serviceKey);
                    } else {
                        // Connection gone, schedule next retry
                        scheduleRetry(connectionId, namespace, group, serviceName, serviceInfo, attempt + 1);
                    }
                } else {
                    log.debug("[harbor] push retry skipped: connection {} no longer exists", connectionId);
                }
            } catch (Exception e) {
                log.warn("[harbor] push retry failed: attempt={}, connId={}", attempt, connectionId, e);
                scheduleRetry(connectionId, namespace, group, serviceName, serviceInfo, attempt + 1);
            } finally {
                pendingRetries.remove(retryKey);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private ConnectionManager.ConnectionRecord findConnection(String connectionId) {
        for (ConnectionManager.ConnectionRecord r : connectionManager.allConnections()) {
            if (connectionId.equals(r.connectionId())) {
                return r;
            }
        }
        return null;
    }

    /**
     * Shut down the retry scheduler.
     */
    public void shutdown() {
        retryScheduler.shutdown();
    }
}
