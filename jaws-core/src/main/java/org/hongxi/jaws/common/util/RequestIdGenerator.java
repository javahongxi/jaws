package org.hongxi.jaws.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates monotonically increasing, JVM-unique request ids.
 *
 * <p>Ids are only used to correlate a request with its response on the
 * same connection, so a plain sequence is sufficient and wrap-around safe.
 */
public final class RequestIdGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private RequestIdGenerator() {
    }

    public static long getRequestId() {
        return SEQUENCE.incrementAndGet();
    }
}
