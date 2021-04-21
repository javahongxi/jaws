package org.hongxi.jaws.protocol.example;

import java.util.concurrent.atomic.AtomicLong;

public class MockWorld implements IWorld {
    public AtomicLong count = new AtomicLong();
    public AtomicLong stringCount = new AtomicLong();
    public AtomicLong sleepCount = new AtomicLong();

    @Override
    public String world() {
        count.incrementAndGet();
        return "mockWorld";
    }

    @Override
    public String world(String world) {
        long num = stringCount.incrementAndGet();
        return world + num;
    }

    @Override
    public String worldSleep(String world, int sleep) {
        long num = sleepCount.incrementAndGet();
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ignore) {}
        return world + num;
    }

}