package org.hongxi.summer.core;

import java.util.concurrent.ThreadFactory;

/**
 * Created by shenhongxi on 2020/7/6.
 */
public class DefaultThreadFactory implements ThreadFactory {

    public DefaultThreadFactory(String prefix, boolean isDaemon) {

    }

    @Override
    public Thread newThread(Runnable r) {
        return null;
    }
}
