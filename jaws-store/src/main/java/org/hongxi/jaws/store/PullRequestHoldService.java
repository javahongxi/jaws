package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long polling: pulls with no new message are held here instead of returning
 * empty. Message arrival (fired by reput AFTER the CQ unit is built) wakes
 * matching requests immediately; a periodic scan is the safety net for
 * missed notifications and deadlines.
 *
 * <p>Latency approaches push, cost approaches pull — the broker keeps no
 * per-consumer push state.
 */
public class PullRequestHoldService {

    private static final Logger logger = LoggerFactory.getLogger(PullRequestHoldService.class);

    /** One suspended pull. {@code completed} guards the wakeup/timeout race:
     * exactly one path may complete the future. */
    static class HoldRequest {
        final String topic;
        final int queueId;
        final long offset;
        final int maxCount;
        final long deadlineMillis;
        final CompletableFuture<PullResult> future = new CompletableFuture<>();
        final AtomicBoolean completed = new AtomicBoolean(false);

        HoldRequest(String topic, int queueId, long offset, int maxCount, long suspendTimeoutMillis) {
            this.topic = topic;
            this.queueId = queueId;
            this.offset = offset;
            this.maxCount = maxCount;
            this.deadlineMillis = System.currentTimeMillis() + suspendTimeoutMillis;
        }
    }

    private final MessageStore messageStore;
    private final int scanIntervalMillis;
    private final ConcurrentMap<String, List<HoldRequest>> holdTable = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private Thread scanThread;

    public PullRequestHoldService(MessageStore messageStore, int scanIntervalMillis) {
        this.messageStore = messageStore;
        this.scanIntervalMillis = scanIntervalMillis;
    }

    public void start() {
        scanThread = new Thread(this::scanLoop, "StoreHoldScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    void suspend(HoldRequest request) {
        holdTable.computeIfAbsent(CommitLog.topicQueueKey(request.topic, request.queueId),
                k -> new CopyOnWriteArrayList<>()).add(request);
    }

    /** Called by reput after the index unit is in place. */
    public void notifyMessageArrive(String topic, int queueId, long maxOffset) {
        List<HoldRequest> requests = holdTable.get(CommitLog.topicQueueKey(topic, queueId));
        if (requests == null) {
            return;
        }
        for (HoldRequest request : requests) {
            if (maxOffset > request.offset) {
                wakeup(request);
            }
        }
    }

    void wakeup(HoldRequest request) {
        if (request.completed.compareAndSet(false, true)) {
            List<HoldRequest> requests = holdTable.get(CommitLog.topicQueueKey(request.topic, request.queueId));
            if (requests != null) {
                requests.remove(request);
            }
            request.future.complete(messageStore.getMessage(
                    request.topic, request.queueId, request.offset, request.maxCount));
        }
    }

    private void scanLoop() {
        while (!stopped) {
            try {
                Thread.sleep(scanIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.currentTimeMillis();
            for (List<HoldRequest> requests : holdTable.values()) {
                for (HoldRequest request : requests) {
                    if (now >= request.deadlineMillis
                            || messageStore.getMaxOffsetInQueue(request.topic, request.queueId) > request.offset) {
                        wakeup(request);
                    }
                }
            }
        }
    }

    public void shutdown() {
        stopped = true;
        if (scanThread != null) {
            scanThread.interrupt();
            try {
                scanThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (List<HoldRequest> requests : holdTable.values()) {
            for (HoldRequest request : requests) {
                wakeup(request);
            }
        }
        logger.info("PullRequestHoldService stopped");
    }
}