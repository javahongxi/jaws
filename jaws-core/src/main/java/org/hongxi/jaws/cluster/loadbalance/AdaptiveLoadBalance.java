package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adaptive load balance based on the power of two choices (P2C).
 * <p>
 * Instead of scanning all references, two distinct candidates are sampled
 * at random and the one with the lower estimated load (average response
 * time x active calls, tracked in a sliding window by {@link LoadEstimator})
 * wins. Random sampling keeps selection cheap while still steering traffic
 * away from slow or busy providers, which matters when the provider list
 * is large and heterogeneous.
 * <p>
 * Created by shenhongxi on 2026/8/23.
 *
 * @see ShortestResponseLoadBalance
 * @see LoadEstimator
 */
@Extension("adaptive")
public class AdaptiveLoadBalance<T> extends AbstractLoadBalance<T> {

    @Override
    protected Reference<T> doSelect(List<Reference<T>> references, Request request) {
        resetWindowIfNeeded();

        List<Reference<T>> available = references.stream()
                .filter(Reference::isAvailable)
                .toList();
        int count = available.size();
        if (count == 0) {
            return null;
        }
        if (count == 1) {
            return available.get(0);
        }

        // sample two distinct candidates at random and pick the less loaded one
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int pos1 = random.nextInt(count);
        int pos2 = random.nextInt(count - 1);
        if (pos2 >= pos1) {
            pos2++;
        }
        return chooseLessLoaded(available.get(pos1), available.get(pos2));
    }

    @Override
    protected void doSelectCandidates(List<Reference<T>> references, Request request,
                                       List<Reference<T>> candidates) {
        resetWindowIfNeeded();

        // order by estimated load so that failover tries the least loaded first
        List<Reference<T>> available = references.stream()
                .filter(Reference::isAvailable)
                .sorted(Comparator.comparingLong(ref -> getEstimator(ref).estimateLoad()))
                .toList();
        for (int i = 0; i < available.size() && candidates.size() < MAX_REFERENCE_COUNT; i++) {
            candidates.add(available.get(i));
        }
    }

    private Reference<T> chooseLessLoaded(Reference<T> first, Reference<T> second) {
        long load1 = getEstimator(first).estimateLoad();
        long load2 = getEstimator(second).estimateLoad();
        if (load1 != load2) {
            return load1 < load2 ? first : second;
        }
        // break the tie by warm-up weight so freshly started providers ramp up gradually
        int weight1 = getWarmupWeight(first, 100);
        int weight2 = getWarmupWeight(second, 100);
        int totalWeight = weight1 + weight2;
        if (totalWeight > 0 && ThreadLocalRandom.current().nextInt(totalWeight) >= weight1) {
            return second;
        }
        return first;
    }
}
