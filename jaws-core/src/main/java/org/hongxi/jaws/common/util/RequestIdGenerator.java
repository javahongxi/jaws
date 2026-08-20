package org.hongxi.jaws.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Request ID generator that encodes approximate request time.
 *
 * <pre>
 *     Currently: currentTimeMillis * (2^20) + offset.incrementAndGet()
 *
 *     To get seconds: requestId / (2^20 * 1000)
 * </pre>
 * <p>
 * Created by shenhongxi on 2020/8/22.
 */
public class RequestIdGenerator {
    protected static final AtomicLong offset = new AtomicLong(0);
    protected static final int BITS = 20;
    protected static final long MAX_COUNT_PER_MILLIS = 1 << BITS;

    public static long getRequestId() {
        long currentTime = System.currentTimeMillis();
        long count = offset.incrementAndGet();
        while (count >= MAX_COUNT_PER_MILLIS) {
            synchronized (RequestIdGenerator.class) {
                if (offset.get() >= MAX_COUNT_PER_MILLIS) {
                    offset.set(0);
                }
            }
            count = offset.incrementAndGet();
        }
        return (currentTime << BITS) + count;
    }
}