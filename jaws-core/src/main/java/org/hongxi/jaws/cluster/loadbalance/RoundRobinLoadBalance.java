package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.MathUtils;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 
 * Round robin loadbalance.
 * 
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "roundrobin")
public class RoundRobinLoadBalance<T> extends AbstractLoadBalance<T> {

    private AtomicInteger idx = new AtomicInteger(0);

    @Override
    protected Referer<T> doSelect(Request request) {
        List<Referer<T>> referers = getReferers();

        int index = getNextNonNegative();
        for (int i = 0; i < referers.size(); i++) {
            Referer<T> ref = referers.get((i + index) % referers.size());
            if (ref.isAvailable()) {
                return ref;
            }
        }
        return null;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Referer<T>> refersHolder) {
        List<Referer<T>> referers = getReferers();

        int index = getNextNonNegative();
        for (int i = 0, count = 0; i < referers.size() && count < MAX_REFERER_COUNT; i++) {
            Referer<T> referer = referers.get((i + index) % referers.size());
            if (referer.isAvailable()) {
                refersHolder.add(referer);
                count++;
            }
        }
    }

    // get non-negative int
    private int getNextNonNegative() {
        return MathUtils.getNonNegative(idx.incrementAndGet());
    }
}
