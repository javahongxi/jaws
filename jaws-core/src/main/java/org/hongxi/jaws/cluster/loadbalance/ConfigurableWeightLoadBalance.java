package org.hongxi.jaws.cluster.loadbalance;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.CollectionUtils;
import org.hongxi.jaws.common.util.MathUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权重可配置的负载均衡器
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
@SpiMeta(name = "configurableWeight")
public class ConfigurableWeightLoadBalance<T> extends ActiveWeightLoadBalance<T> {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableWeightLoadBalance.class);

    private final ReferenceListCacheHolder<T> emptyHolder = new EmptyHolder<>();

    private volatile ReferenceListCacheHolder<T> holder = emptyHolder;

    private String weightString;

    @Override
    public void onRefresh(List<Reference<T>> references) {
        super.onRefresh(references);

        if (CollectionUtils.isEmpty(references)) {
            holder = emptyHolder;
        } else if (StringUtils.isEmpty(weightString)) {
            holder = new SingleGroupHolder<T>(references);
        } else {
            holder = new MultiGroupHolder<T>(weightString, references);
        }
    }

    @Override
    protected Reference<T> doSelect(Request request) {
        if (holder == emptyHolder) {
            return null;
        }

        ReferenceListCacheHolder<T> h = this.holder;
        Reference<T> r = h.next();
        if (!r.isAvailable()) {
            int retryTimes = getReferences().size() - 1;
            for (int i = 0; i < retryTimes; i++) {
                r = h.next();
                if (r.isAvailable()) {
                    break;
                }
            }
        }
        if (r.isAvailable()) {
            return r;
        } else {
            noAvailableReference();
            return null;
        }
    }

    @Override
    protected void doSelectToHolder(Request request, List<Reference<T>> refersHolder) {
        if (holder == emptyHolder) {
            return;
        }

        ReferenceListCacheHolder<T> h = this.holder;
        int i = 0, j = 0;
        while (i++ < getReferences().size()) {
            Reference<T> r = h.next();
            if (r.isAvailable()) {
                refersHolder.add(r);
                if (++j == MAX_REFERENCE_COUNT) {
                    return;
                }
            }
        }
        if (refersHolder.isEmpty()) {
            noAvailableReference();
        }
    }

    private void noAvailableReference() {
        log.error("{} 当前没有可用连接, pool.size={}", this.getClass().getSimpleName(), getReferences().size());
    }

    @Override
    public void setWeightString(String weightString) {
        this.weightString = weightString;
    }


    static abstract class ReferenceListCacheHolder<T> {
        abstract Reference<T> next();
    }

    static class EmptyHolder<T> extends ReferenceListCacheHolder<T> {
        @Override
        Reference<T> next() {
            return null;
        }
    }

    class SingleGroupHolder<T> extends ReferenceListCacheHolder<T> {

        private int size;
        private List<Reference<T>> cache;

        SingleGroupHolder(List<Reference<T>> list) {
            cache = list;
            size = list.size();
            log.info("ConfigurableWeightLoadBalance build new SingleGroupHolder.");
        }

        @Override
        Reference<T> next() {
            return cache.get(ThreadLocalRandom.current().nextInt(size));
        }
    }

    class MultiGroupHolder<T> extends ReferenceListCacheHolder<T> {

        private int randomKeySize = 0;
        private List<String> randomKeyList = new ArrayList<>();
        private Map<String, AtomicInteger> cursors = new HashMap<>();
        private Map<String, List<Reference<T>>> groupReferences = new HashMap<>();

        MultiGroupHolder(String weights, List<Reference<T>> list) {
            log.info("ConfigurableWeightLoadBalance build new MultiGroupHolder. weights:{}", weights);
            String[] groupsAndWeights = weights.split(",");
            int[] weightsArr = new int[groupsAndWeights.length];
            Map<String, Integer> weightsMap = new HashMap<>(groupsAndWeights.length);
            int i = 0;
            for (String groupAndWeight : groupsAndWeights) {
                String[] gw = groupAndWeight.split(":");
                if (gw.length == 2) {
                    Integer w = Integer.valueOf(gw[1]);
                    weightsMap.put(gw[0], w);
                    groupReferences.put(gw[0], new ArrayList<>());
                    weightsArr[i++] = w;
                }
            }

            // 求出最大公约数，若不为1，对权重做除法
            int weightGcd = findGcd(weightsArr);
            if (weightGcd != 1) {
                for (Map.Entry<String, Integer> entry : weightsMap.entrySet()) {
                    weightsMap.put(entry.getKey(), entry.getValue() / weightGcd);
                }
            }

            for (Map.Entry<String, Integer> entry : weightsMap.entrySet()) {
                for (int j = 0; j < entry.getValue(); j++) {
                    randomKeyList.add(entry.getKey());
                }
            }
            Collections.shuffle(randomKeyList);
            randomKeySize = randomKeyList.size();

            for (String key : weightsMap.keySet()) {
                cursors.put(key, new AtomicInteger(0));
            }

            for (Reference<T> reference : list) {
                groupReferences.get(reference.getServiceUrl().getGroup()).add(reference);
            }
        }

        @Override
        Reference<T> next() {
            String group = randomKeyList.get(ThreadLocalRandom.current().nextInt(randomKeySize));
            AtomicInteger ai = cursors.get(group);
            List<Reference<T>> references = groupReferences.get(group);
            return references.get(MathUtils.getNonNegative(ai.getAndIncrement()) % references.size());
        }

        /*
         * 求最大公约数
         */
        private int findGcd(int n, int m) {
            return (n == 0 || m == 0) ? n + m : findGcd(m, n % m);
        }

        /*
         * 求最大公约数
         */
        private int findGcd(int[] arr) {
            int i = 0;
            for (; i < arr.length - 1; i++) {
                arr[i + 1] = findGcd(arr[i], arr[i + 1]);
            }
            return findGcd(arr[i], arr[i - 1]);
        }
    }

}
