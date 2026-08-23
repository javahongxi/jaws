package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weighted round-robin load balance using the smooth algorithm
 * (the same scheme used by nginx upstream).
 * <p>
 * Each round adds the effective weight to every reference's current weight
 * and picks the maximum, then subtracts the total weight from the picked one.
 * Compared with the classic total-weight modulo scheme, selections with the
 * same weights interleave evenly (weights 5:1:1 yield sequences like
 * a,a,b,a,c,a,a instead of a,a,a,a,a,b,c), avoiding request bursts on the
 * highest-weight provider.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@Extension("roundRobin")
public class RoundRobinLoadBalance<T> extends AbstractLoadBalance<T> {

    // smooth WRR state per reference; entries of removed references are pruned at refresh
    private final Map<Reference<T>, SmoothWeight> weights = new ConcurrentHashMap<>();

    @Override
    public void onRefresh(List<Reference<T>> references) {
        super.onRefresh(references);
        weights.keySet().retainAll(references);
    }

    @Override
    protected Reference<T> doSelect(List<Reference<T>> references, Request request) {
        // the increase-pick-subtract sequence must be atomic, otherwise
        // concurrent selections may all pick the same maximum
        synchronized (this) {
            int size = references.size();
            int totalWeight = 0;
            Reference<T> selected = null;
            SmoothWeight selectedWeight = null;

            for (int i = 0; i < size; i++) {
                Reference<T> ref = references.get(i);
                if (!ref.isAvailable()) {
                    continue;
                }
                int weight = getWarmupWeight(ref, 100);
                if (weight <= 0) {
                    continue;
                }
                SmoothWeight smoothWeight = weights.computeIfAbsent(ref, r -> new SmoothWeight());
                smoothWeight.current += weight;
                if (selected == null || smoothWeight.current > selectedWeight.current) {
                    selected = ref;
                    selectedWeight = smoothWeight;
                }
                totalWeight += weight;
            }

            if (selected == null) {
                return null;
            }
            selectedWeight.current -= totalWeight;
            return selected;
        }
    }

    @Override
    protected void doSelectCandidates(List<Reference<T>> references, Request request,
                                        List<Reference<T>> candidates) {
        int index = 0x7fffffff & ThreadLocalRandom.current().nextInt();
        for (int i = 0, count = 0; i < references.size() && count < MAX_REFERENCE_COUNT; i++) {
            Reference<T> reference = references.get((i + index) % references.size());
            if (reference.isAvailable()) {
                candidates.add(reference);
                count++;
            }
        }
    }

    /**
     * Mutable smooth-WRR state of a single reference; guarded by the
     * enclosing load balance instance.
     */
    private static class SmoothWeight {
        int current;
    }
}
