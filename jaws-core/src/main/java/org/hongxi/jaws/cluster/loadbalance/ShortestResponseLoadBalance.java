package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.AbstractReference;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 最短响应负载均衡
 *
 * <pre>
 * 筛选出成功调用中平均响应时间最短的 Reference。
 * 如果只有一个则直接使用；
 * 如果有多个且权重不同，则按权重随机；
 * 如果权重相同，则随机选择一个。
 *
 * 预估响应时间 = 平均响应时间 * (活跃连接数 + 1)
 *
 * 采用滑动窗口机制定期重置统计偏移量，避免历史数据稀释近期趋势。
 * </pre>
 *
 * @see LeastActiveLoadBalance
 */
@SpiMeta(name = "shortestResponse")
public class ShortestResponseLoadBalance<T> extends AbstractLoadBalance<T> {

    /* 滑动窗口周期（毫秒），超过该周期后异步重置偏移量 */
    private static final long SLIDE_PERIOD = 30_000L;

    private final ConcurrentMap<Reference<T>, SlideWindowData> slideWindowMap = new ConcurrentHashMap<>();

    private volatile long lastUpdateTime = System.currentTimeMillis();

    private final AtomicBoolean resetting = new AtomicBoolean(false);

    @Override
    protected Reference<T> doSelect(Request request) {
        List<Reference<T>> references = getReferences();
        int length = references.size();

        long shortestResponse = Long.MAX_VALUE;
        int shortestCount = 0;
        int[] shortestIndexes = new int[length];
        int[] weights = new int[length];
        int totalWeight = 0;
        int firstWeight = 0;
        boolean sameWeight = true;

        for (int i = 0; i < length; i++) {
            Reference<T> ref = references.get(i);
            if (!ref.isAvailable()) {
                continue;
            }

            SlideWindowData data = slideWindowMap.computeIfAbsent(ref, SlideWindowData::new);
            long estimateResponse = data.getEstimateResponse(ref);
            int weight = getWarmupWeight(ref, 100);
            weights[i] = weight;

            if (estimateResponse < shortestResponse) {
                shortestResponse = estimateResponse;
                shortestCount = 1;
                shortestIndexes[0] = i;
                totalWeight = weight;
                firstWeight = weight;
                sameWeight = true;
            } else if (estimateResponse == shortestResponse) {
                shortestIndexes[shortestCount++] = i;
                totalWeight += weight;
                if (sameWeight && i > 0 && weight != firstWeight) {
                    sameWeight = false;
                }
            }
        }

        /* 异步重置滑动窗口偏移量 */
        if (System.currentTimeMillis() - lastUpdateTime > SLIDE_PERIOD
                && resetting.compareAndSet(false, true)) {
            slideWindowMap.values().forEach(SlideWindowData::reset);
            lastUpdateTime = System.currentTimeMillis();
            resetting.set(false);
        }

        if (shortestCount == 1) {
            return references.get(shortestIndexes[0]);
        }
        if (!sameWeight && totalWeight > 0) {
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            for (int i = 0; i < shortestCount; i++) {
                int shortestIndex = shortestIndexes[i];
                offsetWeight -= weights[shortestIndex];
                if (offsetWeight < 0) {
                    return references.get(shortestIndex);
                }
            }
        }
        return references.get(shortestIndexes[ThreadLocalRandom.current().nextInt(shortestCount)]);
    }

    @Override
    protected void doSelectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = getReferences();
        int startIndex = ThreadLocalRandom.current().nextInt(references.size());
        int currentCursor = 0;
        int currentAvailableCursor = 0;

        while (currentAvailableCursor < MAX_REFERENCE_COUNT && currentCursor < references.size()) {
            Reference<T> temp = references.get((startIndex + currentCursor) % references.size());
            currentCursor++;
            if (!temp.isAvailable()) {
                continue;
            }
            currentAvailableCursor++;
            refersHolder.add(temp);
        }
    }

    /**
     * 滑动窗口数据：记录统计偏移量，用于计算窗口期内的平均响应时间
     */
    private static class SlideWindowData {

        private long succeededOffset;
        private long succeededElapsedOffset;
        private final Reference<?> reference;

        SlideWindowData(Reference<?> reference) {
            this.reference = reference;
        }

        void reset() {
            if (reference instanceof AbstractReference<?> ar) {
                succeededOffset = ar.getSucceededCount();
                succeededElapsedOffset = ar.getSucceededElapsed();
            }
        }

        /*
         * 获取窗口期内的平均响应时间（纳秒），若无数据则返回 0
         */
        private long getAverageElapsed() {
            if (!(reference instanceof AbstractReference<?> ar)) {
                return 0;
            }
            long succeed = ar.getSucceededCount() - succeededOffset;
            if (succeed == 0) {
                return 0;
            }
            return (ar.getSucceededElapsed() - succeededElapsedOffset) / succeed;
        }

        /*
         * 预估响应时间 = 平均响应时间 * (活跃连接数 + 1)
         * 活跃连接数越多，预估等待时间越长
         */
        long getEstimateResponse(Reference<?> ref) {
            int active = ref.activeReferenceCount() + 1;
            long avgElapsed = getAverageElapsed();
            if (avgElapsed == 0) {
                /* 尚无调用数据时，用活跃连接数作为启发式估计 */
                return active;
            }
            return avgElapsed * active;
        }
    }
}
