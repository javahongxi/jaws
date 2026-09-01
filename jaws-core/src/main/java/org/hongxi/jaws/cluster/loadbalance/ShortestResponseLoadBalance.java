package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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

    @Override
    protected Reference<T> doSelect(List<Reference<T>> references, Request request) {
        resetWindowIfNeeded();

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

            long estimateResponse = getEstimator(ref).estimateLoad();
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
    protected void doSelectCandidates(List<Reference<T>> references, Request request,
                                        List<Reference<T>> candidates) {
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
}