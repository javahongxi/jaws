package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.lifecycle.ShutdownHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Utility that destroys a group of {@link Reference references} after a fixed delay
 * (1 second by default), giving in-flight requests time to complete before the
 * underlying connections are torn down. Destruction runs on a shared scheduled
 * executor, which is shut down via a JVM shutdown hook.
 *
 * <p>Created by shenhongxi on 2021/3/7.
 */
public class ReferenceDestroyer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDestroyer.class);

    private static final int DELAY_TIME = 1000;
    // A single daemon thread is enough: destroy tasks are rare and sequential
    private static final ScheduledExecutorService scheduledExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jaws-reference-destroyer");
                t.setDaemon(true);
                return t;
            });

    static {
        ShutdownHook.registerShutdownHook(() -> {
            if (!scheduledExecutor.isShutdown()) {
                scheduledExecutor.shutdown();
            }
        });
    }

    public static <T> void delayDestroy(final List<Reference<T>> references) {
        if (references == null || references.isEmpty()) {
            return;
        }

        scheduledExecutor.schedule(() -> {
            for (Reference<?> reference : references) {
                try {
                    reference.destroy();
                } catch (Exception e) {
                    log.error("Failed to destroy reference (delayed): url={}", reference.getUrl().getUri(), e);
                }
            }
            log.info("Delayed reference destroy completed: size={} service={} urls={}",
                    references.size(), references.get(0).getUrl().getIdentity(), getServerPorts(references));
        }, DELAY_TIME, TimeUnit.MILLISECONDS);

        log.info("Delayed reference destroy scheduled in {}ms: size={} service={}",
                DELAY_TIME, references.size(), references.get(0).getUrl().getIdentity());
    }

    private static <T> String getServerPorts(List<Reference<T>> references) {
        if (references == null || references.isEmpty()) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (Reference<T> reference : references) {
            builder.append(reference.getUrl().getHostPort()).append(",");
        }
        builder.setLength(builder.length() - 1);
        builder.append("]");

        return builder.toString();
    }
}