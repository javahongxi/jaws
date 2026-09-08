package org.hongxi.jaws.common.threadpool;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named {@link ThreadFactory} for jaws thread pools, producing threads
 * named {@code <prefix>-<pool>-thread-<n>} with a configurable daemon flag.
 *
 * <p>Each factory instance draws from a shared pool counter, so multiple
 * factories with the same prefix still produce unique, diagnosable thread
 * names.
 *
 * <p>Threads are created directly via {@code new Thread(r)} rather than
 * delegating to {@link java.util.concurrent.Executors#defaultThreadFactory()},
 * because the JDK default factory only sets name, daemon and priority —
 * all of which this factory already controls itself, making the delegation
 * a redundant indirection.
 */
public class DefaultThreadFactory implements ThreadFactory {

    private static final AtomicInteger poolNumber = new AtomicInteger(1);

    private final String namePrefix;
    private final boolean daemon;
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    public DefaultThreadFactory(String prefix) {
        this(prefix, false);
    }

    public DefaultThreadFactory(String prefix, boolean daemon) {
        this.namePrefix = prefix + "-" + poolNumber.getAndIncrement() + "-thread-";
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r);
        thread.setName(namePrefix + threadNumber.getAndIncrement());
        thread.setDaemon(daemon);
        return thread;
    }
}
