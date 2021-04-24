package org.hongxi.jaws.registry.zookeeper;

import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.io.IOException;

/**
 * Created by shenhongxi on 2021/4/24.
 */
public class ZkStartup {

    public static void main(String[] args) throws QuorumPeerConfig.ConfigException, IOException {
        new EmbeddedZookeeper().start();
    }
}
