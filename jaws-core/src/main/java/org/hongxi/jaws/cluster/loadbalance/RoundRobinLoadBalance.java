package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.MathUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * Round robin loadbalance.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "roundRobin")
public class RoundRobinLoadBalance<T> extends AbstractLoadBalance<T> {

    private AtomicInteger idx = new AtomicInteger(0);

    @Override
    protected Reference<T> doSelect(Request request) {
        List<Reference<T>> references = getReferences();
        int size = references.size();

        // calculate total warm-up weight
        int totalWeight = 0;
        int[] weights = new int[size];
        for (int i = 0; i < size; i++) {
            if (references.get(i).isAvailable()) {
                weights[i] = getWarmupWeight(references.get(i), 100);
                totalWeight += weights[i];
            }
        }
        if (totalWeight <= 0) {
            return null;
        }

        // weighted round-robin: advance by a weighted offset
        int index = getNextNonNegative() % totalWeight;
        for (int i = 0; i < size; i++) {
            if (weights[i] <= 0) {
                continue;
            }
            index -= weights[i];
            if (index < 0) {
                return references.get(i);
            }
        }
        // fallback
        for (int i = 0; i < size; i++) {
            if (references.get(i).isAvailable()) {
                return references.get(i);
            }
        }
        return null;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = getReferences();

        int index = getNextNonNegative();
        for (int i = 0, count = 0; i < references.size() && count < MAX_REFERENCE_COUNT; i++) {
            Reference<T> reference = references.get((i + index) % references.size());
            if (reference.isAvailable()) {
                refersHolder.add(reference);
                count++;
            }
        }
    }

    /*
     * get non-negative int
     */
    private int getNextNonNegative() {
        return MathUtils.getNonNegative(idx.incrementAndGet());
    }
}
