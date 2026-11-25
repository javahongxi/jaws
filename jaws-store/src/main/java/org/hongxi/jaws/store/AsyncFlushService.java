package org.hongxi.jaws.store;

/**
 * ASYNC_FLUSH: a metronome forcing dirty pages every interval (500ms by
 * default). PUT_OK only means "in page cache"; durability converges in the
 * background and the OS owns the writeback.
 */
public class AsyncFlushService extends FlushService {

    private final int intervalMillis;

    public AsyncFlushService(CommitLog commitLog, int intervalMillis) {
        super(commitLog, "StoreAsyncFlush");
        this.intervalMillis = intervalMillis;
    }

    @Override
    protected void runLoop() {
        while (!isStopped()) {
            sleep(intervalMillis);
            // the interrupt may be the shutdown signal; do not flush again
            // on the way out, the shutdown path owns the final flush
            if (isStopped()) {
                break;
            }
            commitLog.flush();
        }
    }
}