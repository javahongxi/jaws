package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * random load balance.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@Extension("random")
public class RandomLoadBalance<T> extends AbstractLoadBalance<T> {

    // Computed at refresh time: warm-up weight only deviates from the default
    // within the warm-up window, so no re-evaluation is needed between refreshes.
    private volatile boolean needWeight;

    @Override
    public void onRefresh(List<Reference<T>> references) {
        super.onRefresh(references);
        // fast path eligibility: if no reference is still warming up, all
        // references carry the same weight and selection degrades to pure random
        boolean needWeight = false;
        for (Reference<T> ref : references) {
            if (getWarmupWeight(ref, 100) != 100) {
                needWeight = true;
                break;
            }
        }
        this.needWeight = needWeight;
    }

    @Override
    protected Reference<T> doSelect(Request request) {
        List<Reference<T>> references = getReferences();
        if (!needWeight) {
            return selectRandomAvailable(references);
        }

        // collect available references and their warm-up weights
        int size = references.size();
        int totalWeight = 0;
        int[] weights = new int[size];
        int availableCount = 0;
        int firstWeight = 0;
        boolean sameWeight = true;
        for (int i = 0; i < size; i++) {
            Reference<T> ref = references.get(i);
            if (ref.isAvailable()) {
                int weight = getWarmupWeight(ref, 100);
                weights[i] = weight;
                totalWeight += weight;
                availableCount++;
                if (firstWeight == 0) {
                    firstWeight = weight;
                } else if (sameWeight && weight != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        if (availableCount == 0) {
            return null;
        }
        // equal weights: skip the weighted scan
        if (sameWeight) {
            return selectRandomAvailable(references);
        }

        // weighted random selection
        int offset = ThreadLocalRandom.current().nextInt(Math.max(totalWeight, 1));
        for (int i = 0; i < size; i++) {
            if (weights[i] <= 0) {
                continue;
            }
            offset -= weights[i];
            if (offset < 0) {
                return references.get(i);
            }
        }
        // fallback (should not reach here)
        return selectRandomAvailable(references);
    }

    /**
     * Pick a random available reference; start from a random index and wrap
     * around so that unavailable head elements do not bias the selection.
     */
    private Reference<T> selectRandomAvailable(List<Reference<T>> references) {
        int size = references.size();
        int start = ThreadLocalRandom.current().nextInt(size);
        for (int i = 0; i < size; i++) {
            Reference<T> ref = references.get((start + i) % size);
            if (ref.isAvailable()) {
                return ref;
            }
        }
        return null;
    }

    @Override
    protected void doSelectCandidates(Request request, List<Reference<T>> candidates) {
        List<Reference<T>> references = getReferences();

        int idx = (int) (ThreadLocalRandom.current().nextDouble() * references.size());
        for (int i = 0; i < references.size(); i++) {
            Reference<T> reference = references.get((i + idx) % references.size());
            if (reference.isAvailable()) {
                candidates.add(reference);
            }
        }
    }
}