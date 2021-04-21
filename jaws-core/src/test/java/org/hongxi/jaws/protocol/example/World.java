package org.hongxi.jaws.protocol.example;

public class World implements IWorld {

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