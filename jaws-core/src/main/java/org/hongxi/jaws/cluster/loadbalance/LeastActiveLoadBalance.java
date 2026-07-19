package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "最少活跃" 负载均衡（Least Active）
 *
 * <pre>
 * 		1） 最少活跃调用数优先：reference 的某时刻的活跃 call 数越小优先级越高
 *
 * 		2） 最少活跃 reference 获取策略：
 * 			 由于 Reference List 可能很多，比如上百台，如果每次都要从这上百个 Reference 中选取
 * 			 活跃数最少的，性能有些损耗，因此 random.nextInt(list.size()) 获取一个起始的 index，
 * 			 然后获取最多不超过 MAX_REFERENCE_COUNT 的可用 reference 进行比较。
 * </pre>
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "leastActive")
public class LeastActiveLoadBalance<T> extends AbstractLoadBalance<T> {

    @Override
    protected Reference<T> doSelect(Request request) {
        List<Reference<T>> references = getReferences();

        int referenceSize = references.size();
        int startIndex = ThreadLocalRandom.current().nextInt(referenceSize);
        int currentCursor = 0;
        int currentAvailableCursor = 0;

        Reference<T> reference = null;
        int referenceWeight = 0;

        while (currentAvailableCursor < MAX_REFERENCE_COUNT && currentCursor < referenceSize) {
            Reference<T> temp = references.get((startIndex + currentCursor) % referenceSize);
            currentCursor++;

            if (!temp.isAvailable()) {
                continue;
            }

            currentAvailableCursor++;

            int tempWeight = getWarmupWeight(temp, 100);

            if (reference == null) {
                reference = temp;
                referenceWeight = tempWeight;
            } else {
                // compare by effective active count: activeCount / weight
                // higher weight = more capacity = lower effective load
                if (compare(reference, referenceWeight, temp, tempWeight) > 0) {
                    reference = temp;
                    referenceWeight = tempWeight;
                }
            }
        }

        return reference;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = getReferences();

        int referenceSize = references.size();
        int startIndex = ThreadLocalRandom.current().nextInt(referenceSize);
        int currentCursor = 0;
        int currentAvailableCursor = 0;

        while (currentAvailableCursor < MAX_REFERENCE_COUNT && currentCursor < referenceSize) {
            Reference<T> temp = references.get((startIndex + currentCursor) % referenceSize);
            currentCursor++;

            if (!temp.isAvailable()) {
                continue;
            }

            currentAvailableCursor++;

            refersHolder.add(temp);
        }

        Collections.sort(refersHolder, new LeastActiveComparator<T>());
    }

    private int compare(Reference<T> ref1, int weight1, Reference<T> ref2, int weight2) {
        // effective load = activeCount * (maxWeight / weight)
        // lower effective load is better
        int maxWeight = Math.max(weight1, weight2);
        int effective1 = ref1.activeReferenceCount() * maxWeight / Math.max(weight1, 1);
        int effective2 = ref2.activeReferenceCount() * maxWeight / Math.max(weight2, 1);
        return effective1 - effective2;
    }

    private int compare(Reference<T> reference1, Reference<T> reference2) {
        return reference1.activeReferenceCount() - reference2.activeReferenceCount();
    }

    static class LeastActiveComparator<T> implements Comparator<Reference<T>> {
        @Override
        public int compare(Reference<T> reference1, Reference<T> reference2) {
            return reference1.activeReferenceCount() - reference2.activeReferenceCount();
        }
    }

}
