package org.hongxi.jaws;

import org.hongxi.jaws.common.threadpool.DefaultThreadFactory;
import org.hongxi.jaws.common.threadpool.StandardThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Created by shenhongxi on 2020/8/23.
 */
public class ThreadPoolTest {

    private ThreadPoolExecutor threadPoolExecutor;

    @BeforeEach
    public void setUp() {
        threadPoolExecutor = new StandardThreadPoolExecutor(20, 200, 1000,
                new DefaultThreadFactory("NettyServer", true));
        threadPoolExecutor.prestartAllCoreThreads();
    }

    @AfterEach
    public void clear() {
        threadPoolExecutor.shutdown();
    }

    @Test
    public void submit() {
        threadPoolExecutor.submit(() -> System.out.println(123));
    }
}
