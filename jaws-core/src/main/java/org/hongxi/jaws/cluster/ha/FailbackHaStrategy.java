package org.hongxi.jaws.cluster.ha;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Failback ha strategy.
 * <p>
 * Failed requests are recorded and retried asynchronously at a fixed interval.
 * Suitable for non-critical paths such as notifications, message pushing, and log reporting.
 * <p>
 * Created by shenhongxi on 2025/7/19.
 */
@SpiMeta(name = "failback")
public class FailbackHaStrategy<T> extends AbstractHaStrategy<T> {

    private static final Logger log = LoggerFactory.getLogger(FailbackHaStrategy.class);

    private static final ScheduledExecutorService RETRY_EXECUTOR = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "jaws-failback-retry");
        t.setDaemon(true);
        return t;
    });

    static {
        ShutdownHook.registerShutdownHook(() -> {
            if (!RETRY_EXECUTOR.isShutdown()) {
                RETRY_EXECUTOR.shutdown();
            }
        });
    }

    private final Queue<FailbackTask<T>> failedTasks = new ConcurrentLinkedQueue<>();

    private volatile boolean retryScheduled = false;

    @Override
    public Response call(Request request, LoadBalance<T> loadBalance) {
        Reference<T> refer = loadBalance.select(request);
        try {
            RpcContext.getContext().setServerUrl(refer.getUrl());
            return refer.call(request);
        } catch (RuntimeException e) {
            if (ExceptionUtils.isBizException(e)) {
                throw e;
            }
            log.warn("FailbackHaStrategy call failed, recording for retry: {}", request, e);
            addTask(request, loadBalance);
            DefaultResponse response = new DefaultResponse(request.getRequestId());
            response.setException(e);
            return response;
        }
    }

    private void addTask(Request request, LoadBalance<T> loadBalance) {
        failedTasks.add(new FailbackTask<>(request, loadBalance));
        ensureRetryScheduled();
    }

    private void ensureRetryScheduled() {
        if (!retryScheduled) {
            synchronized (this) {
                if (!retryScheduled) {
                    retryScheduled = true;
                    int period = url != null
                            ? url.getIntParameter(URLParamType.failbackPeriod.getName(), URLParamType.failbackPeriod.intValue())
                            : URLParamType.failbackPeriod.intValue();
                    RETRY_EXECUTOR.scheduleAtFixedRate(this::retry, period, period, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void retry() {
        int size = failedTasks.size();
        if (size == 0) {
            return;
        }
        for (int i = 0; i < size; i++) {
            FailbackTask<T> task = failedTasks.poll();
            if (task == null) {
                break;
            }
            try {
                Reference<T> refer = task.loadBalance.select(task.request);
                RpcContext.getContext().setServerUrl(refer.getUrl());
                task.request.setRetries(task.retryCount + 1);
                Response response = refer.call(task.request);
                // check if the response carries an exception (e.g. remote error)
                if (response.getException() != null) {
                    throw new RuntimeException(response.getException());
                }
                log.info("FailbackHaStrategy retry success for request: {}, retries: {}", task.request, task.retryCount + 1);
            } catch (RuntimeException e) {
                if (ExceptionUtils.isBizException(e)) {
                    log.warn("FailbackHaStrategy retry got biz exception, discarding task: {}", task.request, e);
                    continue;
                }
                task.retryCount++;
                int maxRetries = URLParamType.retries.intValue();
                if (task.retryCount >= maxRetries) {
                    log.error("FailbackHaStrategy retry exhausted after {} attempts, discarding: {}", maxRetries, task.request);
                } else {
                    log.warn("FailbackHaStrategy retry failed, re-queuing: {}, retryCount: {}", task.request, task.retryCount);
                    failedTasks.add(task);
                }
            }
        }
    }

    private static class FailbackTask<T> {
        final Request request;
        final LoadBalance<T> loadBalance;
        int retryCount;

        FailbackTask(Request request, LoadBalance<T> loadBalance) {
            this.request = request;
            this.loadBalance = loadBalance;
            this.retryCount = 0;
        }
    }
}
