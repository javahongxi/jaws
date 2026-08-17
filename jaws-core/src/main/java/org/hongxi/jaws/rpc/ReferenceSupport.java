package org.hongxi.jaws.rpc;

import org.hongxi.jaws.lifecycle.ShutdownHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by shenhongxi on 2021/3/7.
 */
public class ReferenceSupport {

    private static final Logger log = LoggerFactory.getLogger(ReferenceSupport.class);

    private static final int DELAY_TIME = 1000;
    private static final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(10);

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
                    log.error("ReferenceSupport delayDestroy Error: url={}", reference.getUrl().getUri(), e);
                }
            }
        }, DELAY_TIME, TimeUnit.MILLISECONDS);

        log.info("ReferenceSupport delayDestroy Success: size={} service={} urls={}",
                references.size(), references.get(0).getUrl().getIdentity(), getServerPorts(references));
    }

    private static <T> String getServerPorts(List<Reference<T>> references) {
        if (references == null || references.isEmpty()) {
            return "[]";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (Reference<T> reference : references) {
            builder.append(reference.getUrl().getServerPortStr()).append(",");
        }
        builder.setLength(builder.length() - 1);
        builder.append("]");

        return builder.toString();
    }
}