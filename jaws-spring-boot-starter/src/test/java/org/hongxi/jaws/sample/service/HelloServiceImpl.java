package org.hongxi.jaws.sample.service;

public class HelloServiceImpl implements HelloService {

    @Override
    public String world() {
        return "void";
    }

    @Override
    public String world(String world) {
        return world;
    }

    public String worldSleep(String world, int sleep) {
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return world;
    }
}