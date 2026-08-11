package org.hongxi.jaws.registry.zookeeper;

/**
 * Created by shenhongxi on 2021/4/24.
 */
public enum ZkNodeType {

    AVAILABLE_SERVER("server"),
    CLIENT("client");

    private final String value;

    ZkNodeType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}