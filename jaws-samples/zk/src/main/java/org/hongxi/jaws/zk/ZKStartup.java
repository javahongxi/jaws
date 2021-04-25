package org.hongxi.jaws.zk;

import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.io.InputStream;
import java.util.Properties;

public class ZKStartup {

    public static void main(String[] args) throws Exception {
        QuorumPeerConfig quorumPeerConfig = new QuorumPeerConfig();
        InputStream inputStream = ZKStartup.class.getResourceAsStream("/zookeeper.properties");
        Properties properties = new Properties();
        properties.load(inputStream);
        quorumPeerConfig.parseProperties(properties);
        ServerConfig serverConfig = new ServerConfig();
        serverConfig.readFrom(quorumPeerConfig);
        new ZooKeeperServerMain().runFromConfig(serverConfig);
    }
}