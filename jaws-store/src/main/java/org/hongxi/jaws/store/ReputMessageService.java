package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The asynchronous dispatcher tailing the CommitLog: reads records in
 * physical order and hands them to the store, which builds ConsumeQueue
 * units and then notifies long polling. The write path never waits for
 * index construction — otherwise sequential writes would be dragged down
 * by random index writes.
 *
 * <p>Ordering contract (mirrors RocketMQ's ReputMessageService): the listener
 * must finish index dispatch BEFORE firing message-arrival notifications, so
 * a woken-up pull always finds its index ready.
 *
 * <p>Integrity contract: dispatch is acknowledged per unit. A unit the listener
 * could not write holds the position — the record is retried instead of skipped,
 * because a skipped slot becomes a permanent zero unit that every later pull
 * reads back as commitLogOffset 0 / size 0.
 */
public class ReputMessageService {

    private static final Logger logger = LoggerFactory.getLogger(ReputMessageService.class);

    /** Idle poll step, and the retry spacing bounds for a held position. */
    private static final long IDLE_SLEEP_MILLIS = 1;
    private static final long RETRY_BACKOFF_MIN_MILLIS = 50;
    private static final long RETRY_BACKOFF_MAX_MILLIS = 1000;

    /** Implemented by the store: build CQ first, then notify arrival. */
    public interface DispatchListener {
        /**
         * @return true when the index unit landed; false keeps the reput position
         *         on this record so it gets retried after a backoff
         */
        boolean onDispatch(DispatchEntry entry);
    }

    private final CommitLog commitLog;
    private final DispatchListener listener;
    private volatile long reputFromOffset;
    private volatile boolean stopped;
    private Thread thread;

    public ReputMessageService(CommitLog commitLog, DispatchListener listener) {
        this.commitLog = commitLog;
        this.listener = listener;
    }

    public void setReputFromOffset(long reputFromOffset) {
        this.reputFromOffset = reputFromOffset;
    }

    /**
     * Current dispatch position — the offset up to which records have been read
     * and indexed. Doubles as the reput lag basis (maxOffset minus this) and as
     * the safety boundary that CommitLog cleanup must never reclaim behind.
     */
    public long getReputFromOffset() {
        return reputFromOffset;
    }

    public void start() {
        thread = new Thread(this::runLoop, "StoreReput");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        long retryBackoffMillis = RETRY_BACKOFF_MIN_MILLIS;
        boolean stalled = false;
        while (!stopped) {
            boolean dispatched = false;
            boolean indexFailed = false;
            while (!stopped && reputFromOffset < commitLog.getMaxOffset()) {
                DispatchEntry entry = commitLog.readEntry(reputFromOffset);
                if (entry == null) {
                    // jump over the unused tail of a rolled file
                    long next = commitLog.skipFileTail(reputFromOffset);
                    if (next > reputFromOffset && next <= commitLog.getMaxOffset()) {
                        reputFromOffset = next;
                        continue;
                    }
                    break;
                }
                if (!listener.onDispatch(entry)) {
                    indexFailed = true;
                    break;
                }
                reputFromOffset += entry.getSize();
                dispatched = true;
            }
            if (indexFailed) {
                if (stopped) {
                    // Teardown: the interrupt behind this failure is the shutdown
                    // itself, so neither retry nor warn — the index is derived and
                    // rebuilt by replay on the next start.
                    break;
                }
                // Transient by nature (disk pressure, a directory that only just
                // became unwritable), so retry rather than abandon the tail. The
                // spacing widens so a fault that will not clear cannot turn into
                // a hot loop of failed file creations.
                if (!stalled) {
                    logger.warn("Reput held at offset {}: index dispatch failed, retrying", reputFromOffset);
                    stalled = true;
                }
                try {
                    Thread.sleep(retryBackoffMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                retryBackoffMillis = Math.min(retryBackoffMillis * 2, RETRY_BACKOFF_MAX_MILLIS);
                continue;
            }
            stalled = false;
            retryBackoffMillis = RETRY_BACKOFF_MIN_MILLIS;
            if (!dispatched) {
                try {
                    Thread.sleep(IDLE_SLEEP_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logger.info("ReputMessageService stopped at offset {}", reputFromOffset);
    }

    public void shutdown() {
        stopped = true;
        if (thread != null) {
            // The loop tests stopped at every unit, so the interrupt only needs to
            // cut short an idle wait or a file creation already in flight.
            thread.interrupt();
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}