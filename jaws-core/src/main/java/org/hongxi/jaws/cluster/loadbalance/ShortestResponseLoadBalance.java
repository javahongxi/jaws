package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.AbstractReference;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shortest response load balance.
 *
 * <pre>
 * Select the Reference with the shortest average response time among successful calls.
 * If only one, use it directly;
 * If multiple with different weights, select by weighted random;
 * If weights are the same, select randomly.
 *
 * Estimated response time = average response time * (active connections + 1)
 *
 * Uses a sliding window mechanism to periodically reset statistics offsets,
 * preventing historical data from diluting recent trends.
 * </pre>
 *
 * @see LeastActiveLoadBalance
 */
@Extension("shortestResponse")
public class ShortestResponseLoadBalance<T> extends AbstractLoadBalance<T> {

    /* Sliding window period (ms); offsets are asynchronously reset after this period */
    private static final long SLIDE_PERIOD = 30_000L;

    private final ConcurrentMap<Reference<T>, SlideWindowData> slideWindowMap = new ConcurrentHashMap<>();

    private volatile long lastUpdateTime = System.currentTimeMillis();

    private final AtomicBoolean resetting = new AtomicBoolean(false);

    @Override
    public void onRefresh(List<Reference<T>> references) {
        super.onRefresh(references);
        // discard statistics of references removed from the list to prevent unbounded growth
        slideWindowMap.keySet().retainAll(new HashSet<>(references));
    }

    @Override
    protected Reference<T> doSelect(Request request) {
        List<Reference<T>> references = getReferences();
        int length = references.size();

        long shortestResponse = Long.MAX_VALUE;
        int shortestCount = 0;
        int[] shortestIndexes = new int[length];
        int[] weights = new int[length];
        int totalWeight = 0;
        int firstWeight = 0;
        boolean sameWeight = true;

        for (int i = 0; i < length; i++) {
            Reference<T> ref = references.get(i);
            if (!ref.isAvailable()) {
                continue;
            }

            SlideWindowData data = slideWindowMap.computeIfAbsent(ref, SlideWindowData::new);
            long estimateResponse = data.getEstimateResponse(ref);
            int weight = getWarmupWeight(ref, 100);
            weights[i] = weight;

            if (estimateResponse < shortestResponse) {
                shortestResponse = estimateResponse;
                shortestCount = 1;
                shortestIndexes[0] = i;
                totalWeight = weight;
                firstWeight = weight;
                sameWeight = true;
            } else if (estimateResponse == shortestResponse) {
                shortestIndexes[shortestCount++] = i;
                totalWeight += weight;
                if (sameWeight && i > 0 && weight != firstWeight) {
                    sameWeight = false;
                }
            }
        }

        /* Asynchronously reset sliding window offsets */
        if (System.currentTimeMillis() - lastUpdateTime > SLIDE_PERIOD
                && resetting.compareAndSet(false, true)) {
            slideWindowMap.values().forEach(SlideWindowData::reset);
            lastUpdateTime = System.currentTimeMillis();
            resetting.set(false);
        }

        if (shortestCount == 1) {
            return references.get(shortestIndexes[0]);
        }
        if (!sameWeight && totalWeight > 0) {
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            for (int i = 0; i < shortestCount; i++) {
                int shortestIndex = shortestIndexes[i];
                offsetWeight -= weights[shortestIndex];
                if (offsetWeight < 0) {
                    return references.get(shortestIndex);
                }
            }
        }
        return references.get(shortestIndexes[ThreadLocalRandom.current().nextInt(shortestCount)]);
    }

    @Override
    protected void doSelectCandidates(Request request, List<Reference<T>> candidates) {
        List<Reference<T>> references = getReferences();
        int startIndex = ThreadLocalRandom.current().nextInt(references.size());
        int currentCursor = 0;
        int currentAvailableCursor = 0;

        while (currentAvailableCursor < MAX_REFERENCE_COUNT && currentCursor < references.size()) {
            Reference<T> temp = references.get((startIndex + currentCursor) % references.size());
            currentCursor++;
            if (!temp.isAvailable()) {
                continue;
            }
            currentAvailableCursor++;
            candidates.add(temp);
        }
    }

    /**
     * Sliding window data: records statistics offsets for computing average response time within the window.
     */
    private static class SlideWindowData {

        // volatile: written by the CAS-winning thread in reset(),
        // read by other threads in getAverageElapsed()
        private volatile long succeededOffset;
        private volatile long succeededElapsedOffset;
        private final Reference<?> reference;

        SlideWindowData(Reference<?> reference) {
            this.reference = reference;
        }

        void reset() {
            if (reference instanceof AbstractReference<?> ar) {
                succeededOffset = ar.getSucceededCount();
                succeededElapsedOffset = ar.getSucceededElapsed();
            }
        }

        /**
         * Get average response time (nanoseconds) within the window; returns 0 if no data.
         */
        private long getAverageElapsed() {
            if (!(reference instanceof AbstractReference<?> ar)) {
                return 0;
            }
            long succeed = ar.getSucceededCount() - succeededOffset;
            if (succeed == 0) {
                return 0;
            }
            return (ar.getSucceededElapsed() - succeededElapsedOffset) / succeed;
        }

        /**
         * Estimated response time = average response time * (active connections + 1)
         * More active connections means longer estimated wait time.
         */
        long getEstimateResponse(Reference<?> ref) {
            int active = ref.activeReferenceCount() + 1;
            long avgElapsed = getAverageElapsed();
            if (avgElapsed == 0) {
                /* No call data yet; use active count as a heuristic estimate */
                return active;
            }
            return avgElapsed * active;
        }
    }
}