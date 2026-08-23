package org.hongxi.jaws.common.threadpool;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Named {@link ThreadFactory} for jaws thread pools, producing threads
 * named {@code <prefix>-<pool>-thread-<n>} with a configurable daemon flag.
 *
 * <p>Each factory instance draws from a shared pool counter, so multiple
 * factories with the same prefix still produce unique, diagnosable thread
 * names. Thread creation itself is delegated to the JDK default factory,
 * with naming and daemon settings adjusted afterwards.
 */
public class DefaultThreadFactory implements ThreadFactory {

    private static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);

    private final ThreadFactory delegate = Executors.defaultThreadFactory();
    private final String namePrefix;
    private final boolean daemon;
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    public DefaultThreadFactory(String prefix) {
        this(prefix, false);
    }

    public DefaultThreadFactory(String prefix, boolean daemon) {
        this.namePrefix = prefix + "-" + POOL_NUMBER.getAndIncrement() + "-thread-";
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = delegate.newThread(r);
        thread.setName(namePrefix + threadNumber.getAndIncrement());
        thread.setDaemon(daemon);
        return thread;
    }
}
