package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.proto.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Service-level, coalescing push engine — the jaws-harbor counterpart of Nacos
 * naming push v2 ({@code PushDelayTaskExecuteEngine} + {@code PushExecuteTask}).
 * <p>
 * Model: a change to a service enqueues <em>one</em> delayed, coalesced task per
 * service key. When the task fires it <b>re-reads the current {@link ServiceInfo}
 * and the current subscriber set</b> — never a snapshot captured at change time —
 * and pushes that latest state to each still-connected subscriber. A push whose
 * connection has gone is skipped (the client re-subscribes with the full list on
 * reconnect); only an unexpected error during the pass is re-enqueued with a fixed
 * delay.
 * <p>
 * This is idempotent convergence, not per-push ack — Nacos's
 * {@code NotifySubscriberResponse} is an empty ack, so no application-layer
 * delivery signal exists. Because every push carries the <em>current</em> full
 * state, a dropped notification is healed by the next change or the client's own
 * polling, and out-of-order retries can never regress a subscriber to a stale
 * instance list. That is precisely why we do NOT cache the payload to resend it.
 *
 * @author shenhongxi
 */
public class PushDelayTaskEngine {

    private static final Logger log = LoggerFactory.getLogger(PushDelayTaskEngine.class);

    /** payload {@code metadata.type} for a naming change notification. */
    private static final String TYPE_NOTIFY_SUBSCRIBER_REQUEST = "NotifySubscriberRequest";

    /** Coalescing window: bursts on the same service collapse into one push. */
    private static final long MERGE_DELAY_MS = 200L;

    /** Re-enqueue delay on an unexpected error. Fixed, not exponential (matches Nacos). */
    private static final long RETRY_DELAY_MS = 1000L;

    private final ServiceStorage serviceStorage;
    private final ConnectionManager connectionManager;

    /** Set by {@link #shutdown()}; a closed engine drops pushes instead of throwing. */
    private volatile boolean closed;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "harbor-push-engine");
                t.setDaemon(true);
                return t;
            });

    /** serviceKey -> the single in-flight task for it; presence = coalescing lock. */
    private final Map<String, PendingPush> pending = new ConcurrentHashMap<>();

    public PushDelayTaskEngine(ServiceStorage serviceStorage, ConnectionManager connectionManager) {
        this.serviceStorage = serviceStorage;
        this.connectionManager = connectionManager;
    }

    /**
     * Register that a service changed. Idempotent and coalescing: if a push for
     * this service is already scheduled, this is a no-op — that pending push will
     * re-read the latest state when it fires, so it already covers this change.
     */
    public void requestPush(String namespace, String group, String serviceName) {
        String serviceKey = serviceKey(namespace, group, serviceName);
        if (closed) {
            log.debug("[harbor] push request dropped, engine closed: {}", serviceKey);
            return;
        }
        pending.computeIfAbsent(serviceKey, k -> {
            PendingPush task = new PendingPush(namespace, group, serviceName);
            task.future = scheduleQuietly(k, () -> doPush(k), MERGE_DELAY_MS);
            return task;
        });
    }

    /**
     * Arm a push, tolerating a concurrent {@link #shutdown()}. Inbound frames keep
     * arriving while the server is closing, so a request can land after the engine is
     * gone; a rejected schedule must not travel back out of the caller's hands — it
     * reaches a Netty worker thread, where the transport can only report it as an
     * unexpected channel error. If this loses the race, the entry left behind is inert
     * and dies with the engine.
     *
     * @return the scheduled future, or {@code null} if the engine is shutting down
     */
    private java.util.concurrent.ScheduledFuture<?> scheduleQuietly(String serviceKey,
                                                                    Runnable task, long delayMs) {
        try {
            return scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("[harbor] push scheduling rejected, engine closing: {}", serviceKey);
            return null;
        }
    }

    private void doPush(String serviceKey) {
        PendingPush task = pending.remove(serviceKey);
        if (task == null) {
            return;
        }
        try {
            // Re-read the CURRENT state at fire time — the whole point of reconcile.
            ServiceInfo latest = serviceStorage.buildServiceInfo(
                    task.namespace, task.group, task.serviceName);

            NotifySubscriberRequest push = new NotifySubscriberRequest();
            push.setNamespace(task.namespace);
            push.setServiceName(task.serviceName);
            push.setGroupName(task.group);
            push.setServiceInfo(latest);

            Payload payload = HarborServer.buildPushPayload(
                    TYPE_NOTIFY_SUBSCRIBER_REQUEST, push);

            for (String connId : serviceStorage.getSubscriberConnections(serviceKey)) {
                // pushToConnection returns false only when the connection is gone;
                // a gone client re-subscribes on reconnect, so skip — do not retry.
                boolean connected = connectionManager.pushToConnection(connId, payload);
                if (!connected) {
                    log.debug("[harbor] push skipped, connection gone: connId={}, service={}",
                            connId, serviceKey);
                }
            }
        } catch (Exception e) {
            // Unexpected error in the pass itself (not a "connection gone"): re-enqueue.
            log.warn("[harbor] push pass failed for {}, re-enqueue in {}ms",
                    serviceKey, RETRY_DELAY_MS, e);
            task.future = scheduleQuietly(serviceKey, () -> doPush(serviceKey), RETRY_DELAY_MS);
            if (task.future != null) {
                pending.put(serviceKey, task);
            }
        }
    }

    public void shutdown() {
        // Announce before stopping the pool: requesters check this flag, and a push
        // that slips past it is handled by scheduleQuietly.
        closed = true;
        scheduler.shutdownNow();
        pending.clear();
    }

    private static String serviceKey(String namespace, String group, String serviceName) {
        return namespace + "@@" + group + "@@" + serviceName;
    }

    /** A coalesced, in-flight push for one service. */
    private static final class PendingPush {
        final String namespace;
        final String group;
        final String serviceName;
        volatile ScheduledFuture<?> future;

        PendingPush(String namespace, String group, String serviceName) {
            this.namespace = namespace;
            this.group = group;
            this.serviceName = serviceName;
        }
    }
}
