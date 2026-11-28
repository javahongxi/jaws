package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Background flush driver of the CommitLog. Two strategies share this base:
 * a periodic ticker (ASYNC_FLUSH) and a group-commit batcher (SYNC_FLUSH).
 */
public abstract class FlushService {

    private static final Logger log = LoggerFactory.getLogger(FlushService.class);

    protected final CommitLog commitLog;
    private final String serviceName;
    private volatile boolean stopped;
    private Thread thread;

    protected FlushService(CommitLog commitLog, String serviceName) {
        this.commitLog = commitLog;
        this.serviceName = serviceName;
    }

    /**
     * Durability barrier for an append ending at nextOffset. The async
     * strategy completes immediately; group commit completes after fsync.
     */
    public CompletableFuture<Void> submitFlush(long nextOffset) {
        return CompletableFuture.completedFuture(null);
    }

    public void start() {
        thread = new Thread(this::runLoop, serviceName);
        thread.setDaemon(true);
        thread.start();
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
        commitLog.flush();
        log.info("{} stopped", serviceName);
    }

    protected boolean isStopped() {
        return stopped;
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected abstract void runLoop();
}