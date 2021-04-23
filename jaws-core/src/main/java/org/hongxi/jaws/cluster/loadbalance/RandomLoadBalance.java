package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.Referer;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 
 * random load balance.
 *
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "random")
public class RandomLoadBalance<T> extends AbstractLoadBalance<T> {

    @Override
    protected Referer<T> doSelect(Request request) {
        List<Referer<T>> referers = getReferers();

        int idx = (int) (ThreadLocalRandom.current().nextDouble() * referers.size());
        for (int i = 0; i < referers.size(); i++) {
            Referer<T> ref = referers.get((i + idx) % referers.size());
            if (ref.isAvailable()) {
                return ref;
            }
        }
        return null;
    }

    @Override
    protected void doSelectToHolder(Request request, List<Referer<T>> refersHolder) {
        List<Referer<T>> referers = getReferers();

        int idx = (int) (ThreadLocalRandom.current().nextDouble() * referers.size());
        for (int i = 0; i < referers.size(); i++) {
            Referer<T> referer = referers.get((i + idx) % referers.size());
            if (referer.isAvailable()) {
                refersHolder.add(referer);
            }
        }
    }
}
