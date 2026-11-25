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
 */
public class ReputMessageService {

    private static final Logger logger = LoggerFactory.getLogger(ReputMessageService.class);

    /** Implemented by the store: build CQ first, then notify arrival. */
    public interface DispatchListener {
        void onDispatch(DispatchEntry entry);
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

    public void start() {
        thread = new Thread(this::runLoop, "StoreReput");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        while (!stopped) {
            boolean dispatched = false;
            while (reputFromOffset < commitLog.getMaxOffset()) {
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
                listener.onDispatch(entry);
                reputFromOffset += entry.getSize();
                dispatched = true;
            }
            if (!dispatched) {
                try {
                    Thread.sleep(1);
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
            thread.interrupt();
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}