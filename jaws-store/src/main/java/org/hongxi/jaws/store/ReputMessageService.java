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
 *
 * <p>Bounded tolerance: holding forever would let a permanent fault grow an
 * unbounded CommitLog that the index can never serve, so after
 * {@code dispatchMaxRetries} attempts on the same unit the listener is told the
 * index is broken and gates its write path; attempts keep going, and the first
 * unit that lands reports the index healthy again, lifting the gate.
 */
public class ReputMessageService {

    private static final Logger log = LoggerFactory.getLogger(ReputMessageService.class);

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

        /**
         * Edge notification around the retry bound of the held unit: false once
         * attempts on it exceeded the bound (stop accepting writes), true once a
         * retried unit lands after such a break.
         */
        void onIndexHealthChanged(boolean healthy);
    }

    private final CommitLog commitLog;
    private final DispatchListener listener;
    private final int dispatchMaxRetries;
    private volatile long reputFromOffset;
    private volatile boolean stopped;
    private Thread thread;

    public ReputMessageService(CommitLog commitLog, DispatchListener listener, int dispatchMaxRetries) {
        this.commitLog = commitLog;
        this.listener = listener;
        this.dispatchMaxRetries = Math.max(1, dispatchMaxRetries);
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
        boolean fused = false;
        // Attempts are counted per held unit, so the position itself is the key: any
        // advance, including a jump over a rolled file's tail, starts a fresh budget.
        long heldAtOffset = -1;
        int heldAttempts = 0;
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
                if (fused) {
                    fused = false;
                    log.info("Index dispatch healed at offset {}: write path re-opened", reputFromOffset);
                    listener.onIndexHealthChanged(true);
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
                if (heldAtOffset != reputFromOffset) {
                    heldAtOffset = reputFromOffset;
                    heldAttempts = 0;
                }
                heldAttempts++;
                // Transient by nature (disk pressure, a directory that only just
                // became unwritable), so retry rather than abandon the tail. The
                // spacing widens so a fault that will not clear cannot turn into
                // a hot loop of failed file creations.
                if (!stalled) {
                    log.warn("Reput held at offset {}: index dispatch failed, retrying", reputFromOffset);
                    stalled = true;
                }
                if (!fused && heldAttempts >= dispatchMaxRetries) {
                    // The escalation RocketMQ reaches for in the same situation, minus its
                    // skip: after 30 attempts it calls makeLogicsQueueError, which both stops
                    // accepting writes and lets the dispatcher step over the unit. Stepping
                    // over is what cannot be afforded here (zero unit in an array-addressed
                    // queue), so only the write gate is taken and the position keeps being held.
                    fused = true;
                    log.error("Reput exceeded {} attempts on offset {}: index declared broken, write path fused",
                            dispatchMaxRetries, reputFromOffset);
                    listener.onIndexHealthChanged(false);
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
            heldAtOffset = -1;
            heldAttempts = 0;
            if (!dispatched) {
                try {
                    Thread.sleep(IDLE_SLEEP_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("ReputMessageService stopped at offset {}", reputFromOffset);
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