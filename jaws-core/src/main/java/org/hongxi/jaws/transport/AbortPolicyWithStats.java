package org.hongxi.jaws.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Rejection policy shared by all server business pools: log the pool
 * statistics for diagnosis, then throw so the submitting pipeline handler
 * can answer the affected request with an error response (fail fast)
 * instead of letting the caller block or the request drop silently.
 * <p>
 * Modeled after Dubbo's {@code AbortPolicyWithReport}; the per-request
 * error response is built by the handler that catches the exception, since
 * only it holds the request context.
 *
 * @author shenhongxi
 */
public class AbortPolicyWithStats extends ThreadPoolExecutor.AbortPolicy {
    private static final Logger log = LoggerFactory.getLogger(AbortPolicyWithStats.class);

    private final String poolName;

    public AbortPolicyWithStats(String poolName) {
        this.poolName = poolName;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor pool) {
        log.error("Thread pool is EXHAUSTED! name={} poolSize={} (active: {}, core: {}, max: {}, largest: {}),"
                        + " task: {} (completed: {}), isShutdown: {}, isTerminated: {}",
                poolName,
                pool.getPoolSize(), pool.getActiveCount(),
                pool.getCorePoolSize(), pool.getMaximumPoolSize(), pool.getLargestPoolSize(),
                pool.getTaskCount(), pool.getCompletedTaskCount(),
                pool.isShutdown(), pool.isTerminated());
        super.rejectedExecution(r, pool);
    }
}
