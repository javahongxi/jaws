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

        int index = getNextNonNegative();
        for (int i = 0; i < references.size(); i++) {
            Reference<T> ref = references.get((i + index) % references.size());
            if (ref.isAvailable()) {
                return ref;
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
