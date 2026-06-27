package org.hongxi.jaws.closeable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Add a shutdown hook to close some global resources
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
public class ShutdownHook extends Thread {

    private static final Logger log = LoggerFactory.getLogger(ShutdownHook.class);

    // Smaller the priority is,earlier the resource is to be closed,default Priority is 20
    private static final int DEFAULT_PRIORITY = 20;
    // only global resource should be register to ShutDownHook,don't register connections to it.
    private static ShutdownHook instance;
    private List<CloseableObject> resourceList = new ArrayList<>();

    private ShutdownHook() {
    }

    private static void init() {
        if (instance == null) {
            instance = new ShutdownHook();
            log.info("ShutdownHook is initialized");
        }
    }

    public static void runHook(boolean sync) {
        if (instance != null) {
            if (sync) {
                instance.run();
            } else {
                instance.start();
            }
        }
    }

    public static void registerShutdownHook(Closeable closeable) {
        registerShutdownHook(closeable, DEFAULT_PRIORITY);
    }

    public static synchronized void registerShutdownHook(Closeable closeable, int priority) {
        if (instance == null) {
            init();
        }
        instance.resourceList.add(new CloseableObject(closeable, priority));
        log.info("add resource {} to list", closeable.getClass());
    }

    @Override
    public void run() {
        closeAll();
    }

    /**
     * synchronized method to close all the resources in the list
     */
    private synchronized void closeAll() {
        Collections.sort(resourceList);
        log.info("Start to close global resource due to priority");
        for (CloseableObject resource : resourceList) {
            try {
                resource.closeable.close();
            } catch (Exception e) {
                log.error("Failed to close {}", resource.closeable.getClass(), e);
            }
            log.info("Success to close {}", resource.closeable.getClass());
        }
        log.info("Success to close all the resource!");
        resourceList.clear();
    }

    private static class CloseableObject implements Comparable<CloseableObject> {
        Closeable closeable;
        int priority;

        public CloseableObject(Closeable closeable, int priority) {
            this.closeable = closeable;
            this.priority = priority;
        }

        @Override
        public int compareTo(CloseableObject o) {
            if (this.priority > o.priority) {
                return -1;
            } else if (this.priority == o.priority) {
                return 0;
            } else {
                return 1;
            }
        }
    }
}
