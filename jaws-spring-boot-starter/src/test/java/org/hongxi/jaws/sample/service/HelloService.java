package org.hongxi.jaws.sample.service;

public interface HelloService {
    String world();

    String world(String world);

    String worldSleep(String world, int sleep);
}