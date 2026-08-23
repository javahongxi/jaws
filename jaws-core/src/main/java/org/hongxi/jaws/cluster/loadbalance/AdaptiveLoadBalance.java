package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /* Sliding window period (ms); offsets are asynchronously reset after this period */
    private static final long SLIDE_PERIOD = 30_000L;

    private final ConcurrentMap<Reference<T>, LoadEstimator> estimators = new ConcurrentHashMap<>();

    private volatile long lastUpdateTime = System.currentTimeMillis();

    private final AtomicBoolean resetting = new AtomicBoolean(false);

    @Override
    public void onRefresh(List<Reference<T>> references) {
        super.onRefresh(references);
        // discard statistics of references removed from the list to prevent unbounded growth
        estimators.keySet().retainAll(new HashSet<>(references));
    }

    @Override
    protected Reference<T> doSelect(List<Reference<T>> references, Request request) {
        resetWindowIfNeeded();

        List<Reference<T>> available = filterAvailable(references);
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

        List<Reference<T>> available = filterAvailable(references);
        // order by estimated load so that failover tries the least loaded first
        available.sort(Comparator.comparingLong(
                ref -> estimators.computeIfAbsent(ref, LoadEstimator::new).estimateLoad()));
        for (int i = 0; i < available.size() && candidates.size() < MAX_REFERENCE_COUNT; i++) {
            candidates.add(available.get(i));
        }
    }

    private List<Reference<T>> filterAvailable(List<Reference<T>> references) {
        List<Reference<T>> available = new ArrayList<>(references.size());
        for (Reference<T> ref : references) {
            if (ref.isAvailable()) {
                available.add(ref);
            }
        }
        return available;
    }

    private Reference<T> chooseLessLoaded(Reference<T> first, Reference<T> second) {
        long load1 = estimators.computeIfAbsent(first, LoadEstimator::new).estimateLoad();
        long load2 = estimators.computeIfAbsent(second, LoadEstimator::new).estimateLoad();
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

    private void resetWindowIfNeeded() {
        if (System.currentTimeMillis() - lastUpdateTime > SLIDE_PERIOD
                && resetting.compareAndSet(false, true)) {
            estimators.values().forEach(LoadEstimator::reset);
            lastUpdateTime = System.currentTimeMillis();
            resetting.set(false);
        }
    }
}
