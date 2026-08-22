package org.hongxi.jaws.registry.zookeeper;

/**
 * Node categories used under each ZooKeeper service path:
 * {@link #AVAILABLE_SERVER} ({@code server}) marks provider nodes written on
 * registration, while {@link #CLIENT} ({@code client}) marks consumer nodes
 * written on subscription. Both are created as ephemeral nodes by
 * {@link ZookeeperRegistry}.
 *
 * @see ZkUtils#toNodeTypePath
 *
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