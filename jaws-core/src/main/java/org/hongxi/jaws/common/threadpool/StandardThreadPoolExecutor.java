package org.hongxi.jaws.common.threadpool;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <pre>
 *
 * Code and ideas mainly inspired by:
 *
 * tomcat :
 * 		org.apache.catalina.core.StandardThreadExecutor
 *
 * java.util.concurrent
 * ThreadPoolExecutor execute strategy:
 *     Priority offer to queue, then expand threads to maxThread when queue is full,
 * 	   reject if already at maxThread.
 * 	   Suitable for CPU-intensive applications (e.g., operations within JVM like memory copy, compute, etc.)
 *
 * StandardThreadExecutor execute strategy:
 *     Priority expand threads to maxThread, then offer to queue,
 * 	   reject if queue is full.
 * 	   Suitable for scenarios where business processing requires remote resources
 *
 * </pre>
 * <p>
 * Created by shenhongxi on 2020/6/27.
 */
public class StandardThreadPoolExecutor extends ThreadPoolExecutor {
    public static final int DEFAULT_MIN_THREADS = 20;
    public static final int DEFAULT_MAX_THREADS = 200;

    public static final int DEFAULT_MAX_IDLE_TIME = 60 * 1000;

    protected AtomicInteger submittedTasksCount;
    private final int maxSubmittedTasks;

    public StandardThreadPoolExecutor() {
        this(DEFAULT_MIN_THREADS, DEFAULT_MAX_THREADS);
    }

    public StandardThreadPoolExecutor(int corePoolSize, int maximumPoolSize) {
        this(corePoolSize, maximumPoolSize, maximumPoolSize);
    }

    public StandardThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, maximumPoolSize);
    }

    public StandardThreadPoolExecutor(int corePoolSize, int maximumPoolSize, int queueCapacity) {
        this(corePoolSize, maximumPoolSize, queueCapacity, Executors.defaultThreadFactory());
    }

    public StandardThreadPoolExecutor(int corePoolSize, int maximumPoolSize, int queueCapacity, ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, DEFAULT_MAX_IDLE_TIME, TimeUnit.MILLISECONDS, queueCapacity, threadFactory);
    }

    public StandardThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, int queueCapacity) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, queueCapacity, Executors.defaultThreadFactory());
    }

    public StandardThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                      int queueCapacity, ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, queueCapacity, threadFactory, new AbortPolicy());
    }

    public StandardThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                      int queueCapacity, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, new ExecutorQueue(), threadFactory, handler);
        ((ExecutorQueue) getQueue()).setThreadPoolExecutor(this);

        submittedTasksCount = new AtomicInteger(0);
        maxSubmittedTasks = maximumPoolSize + queueCapacity;
    }

    @Override
    public void execute(Runnable command) {
        int count = submittedTasksCount.incrementAndGet();
        if (count > maxSubmittedTasks) {
            submittedTasksCount.decrementAndGet();
            getRejectedExecutionHandler().rejectedExecution(command, this);
        }

        try {
            super.execute(command);
        } catch (RejectedExecutionException e) {
            if (!((ExecutorQueue) getQueue()).force(command)) {
                submittedTasksCount.decrementAndGet();
                getRejectedExecutionHandler().rejectedExecution(command, this);
            }
        }
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        submittedTasksCount.decrementAndGet();
    }

    public int getSubmittedTasksCount() {
        return submittedTasksCount.get();
    }
}