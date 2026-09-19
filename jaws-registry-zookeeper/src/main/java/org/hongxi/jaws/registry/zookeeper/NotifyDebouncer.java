package org.hongxi.jaws.registry.zookeeper;

import java.util.function.Consumer;

/**
 * Coalesces rapid updates into a single downstream delivery within a trailing
 * debounce window, so a ZooKeeper child-churn burst on one watched service
 * (many {@code NODE_CREATED}/{@code NODE_DELETED} events) does not become one
 * full re-fetch + notify per event.
 * <p>
 * {@link #offer(Object)} records the newest snapshot and, only if no flush is
 * already pending, schedules a flush {@code delayMs} later; that flush delivers
 * the latest snapshot. A sustained burst therefore yields at most one delivery
 * per {@code delayMs} (a throttle) always carrying the freshest data — bounded,
 * no starvation. {@code delayMs <= 0} disables coalescing and delivers each
 * offer synchronously, preserving the pre-debounce behaviour.
 * <p>
 * Mirrors Dubbo's client-side {@code delay-notification} (its {@code RegistryNotifier}
 * latest-wins / {@code ZookeeperRegistryNotifier} throttle) and Nacos server push's
 * 500ms merge. The scheduler is injected so tests can drive the flush
 * deterministically without sleeping.
 *
 * @param <T> the snapshot type (a resolved provider URL list)
 * @author shenhongxi
 */
final class NotifyDebouncer<T> implements AutoCloseable {

    /** Abstraction over "run this task after a delay", injectable for tests. */
    interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    private final long delayMs;
    private final Scheduler scheduler;
    private final Consumer<T> sink;

    private final Object lock = new Object();
    private T latest;
    private boolean scheduled;
    private boolean closed;

    NotifyDebouncer(long delayMs, Scheduler scheduler, Consumer<T> sink) {
        this.delayMs = delayMs;
        this.scheduler = scheduler;
        this.sink = sink;
    }

    /**
     * Propose a new snapshot. Only the most recent one offered before the pending
     * flush fires is ever delivered.
     */
    void offer(T snapshot) {
        if (delayMs <= 0) {
            sink.accept(snapshot);
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            latest = snapshot;
            if (!scheduled) {
                scheduled = true;
                scheduler.schedule(this::flush, delayMs);
            }
        }
    }

    private void flush() {
        T snapshot;
        synchronized (lock) {
            scheduled = false;
            snapshot = closed ? null : latest;
            latest = null;
        }
        if (snapshot != null) {
            sink.accept(snapshot);
        }
    }

    /** Stop delivering; a late flush finds nothing pending and no-ops. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            latest = null;
        }
    }
}
