package org.hongxi.jaws.common.threadpool;

import java.util.concurrent.LinkedTransferQueue;
import java.io.Serial;
import java.util.concurrent.RejectedExecutionException;

/**
 * LinkedTransferQueue provides higher performance compared to LinkedBlockingQueue
 *
 * <pre>
 * 		1) However, LinkedTransferQueue lacks queue length control, which needs to be managed externally
 * </pre>
 * <p>
 * Created by shenhongxi on 2020/7/6.
 *
 */
public class ExecutorQueue extends LinkedTransferQueue<Runnable> {
    @Serial
    private static final long serialVersionUID = -3392627914941820087L;

    private EagerThreadPoolExecutor threadPoolExecutor;

    public ExecutorQueue() {
        super();
    }

    public void setThreadPoolExecutor(EagerThreadPoolExecutor threadPoolExecutor) {
        this.threadPoolExecutor = threadPoolExecutor;
    }

    public boolean force(Runnable command) {
        if (threadPoolExecutor.isShutdown()) {
            throw new RejectedExecutionException("Executor not running, cannot force a task into the queue");
        }
        return super.offer(command);
    }

    public boolean offer(Runnable command) {
        int poolSize = threadPoolExecutor.getPoolSize();

        if (poolSize == threadPoolExecutor.getMaximumPoolSize()) {
            return super.offer(command);
        }

        if (threadPoolExecutor.getSubmittedTasksCount() <= poolSize) {
            return super.offer(command);
        }

        if (poolSize < threadPoolExecutor.getMaximumPoolSize()) {
            return false;
        }

        return super.offer(command);
    }
}
