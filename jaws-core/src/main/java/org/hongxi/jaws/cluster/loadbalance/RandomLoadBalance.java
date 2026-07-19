package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 *
 * random load balance.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "random")
public class RandomLoadBalance<T> extends AbstractLoadBalance<T> {

    @Override
    protected Reference<T> doSelect(Request request) {
        List<Reference<T>> references = getReferences();
        int size = references.size();

        // collect available references and their warm-up weights
        int totalWeight = 0;
        int[] weights = new int[size];
        int availableCount = 0;
        for (int i = 0; i < size; i++) {
            Reference<T> ref = references.get(i);
            if (ref.isAvailable()) {
                weights[i] = getWarmupWeight(ref, 100);
                totalWeight += weights[i];
                availableCount++;
            }
        }
        if (availableCount == 0) {
            return null;
        }

        // weighted random selection
        int offset = ThreadLocalRandom.current().nextInt(Math.max(totalWeight, 1));
        for (int i = 0; i < size; i++) {
            if (!references.get(i).isAvailable()) {
                continue;
            }
            offset -= weights[i];
            if (offset < 0) {
                return references.get(i);
            }
        }
        // fallback (should not reach here)
        return references.get(ThreadLocalRandom.current().nextInt(size));
    }

    @Override
    protected void doSelectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = getReferences();

        int idx = (int) (ThreadLocalRandom.current().nextDouble() * references.size());
        for (int i = 0; i < references.size(); i++) {
            Reference<T> reference = references.get((i + idx) % references.size());
            if (reference.isAvailable()) {
                refersHolder.add(reference);
            }
        }
    }
}
