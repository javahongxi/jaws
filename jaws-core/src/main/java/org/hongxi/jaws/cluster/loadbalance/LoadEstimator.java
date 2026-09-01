package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.rpc.AbstractReference;
import org.hongxi.jaws.rpc.Reference;

/**
 * Estimates a reference's current load from a sliding window over its
 * successful-call statistics.
 * <p>
 * Estimated load = average response time x (active calls + 1); when the
 * window holds no call data yet, the active-call count alone serves as a
 * heuristic. Counters are snapshotted at {@link #reset}, so only calls
 * after the last reset contribute to the average, preventing long-running
 * history from diluting recent trends.
 * <p>
 * Shared by load balance strategies that need response-time awareness
 * (shortest response, adaptive P2C).
 */
public final class LoadEstimator {

    private final Reference<?> reference;

    // volatile: written by the thread that wins the reset, read by business threads on every select
    private volatile long succeededOffset;
    private volatile long succeededElapsedOffset;

    LoadEstimator(Reference<?> reference) {
        this.reference = reference;
    }

    /**
     * Snapshot the current counters; calls completed before this point stop
     * contributing to the estimated load.
     */
    void reset() {
        if (reference instanceof AbstractReference<?> ar) {
            succeededOffset = ar.getSucceededCount();
            succeededElapsedOffset = ar.getSucceededElapsed();
        }
    }

    /**
     * Estimated load = average response time (ns) x (active calls + 1).
     * More active calls implies a longer expected wait. Falls back to the
     * active-call count when the window holds no data yet.
     */
    long estimateLoad() {
        int active = reference.activeCallCount() + 1;
        long avgElapsed = averageElapsed();
        return avgElapsed == 0 ? active : avgElapsed * active;
    }

    /**
     * Average response time (nanoseconds) of successful calls within the
     * window; 0 if the window holds no data or the reference exposes no
     * statistics.
     */
    private long averageElapsed() {
        if (!(reference instanceof AbstractReference<?> ar)) {
            return 0;
        }
        long succeeded = ar.getSucceededCount() - succeededOffset;
        if (succeeded == 0) {
            return 0;
        }
        return (ar.getSucceededElapsed() - succeededElapsedOffset) / succeeded;
    }
}
