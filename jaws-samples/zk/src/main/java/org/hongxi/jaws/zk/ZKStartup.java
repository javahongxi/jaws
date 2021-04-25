package org.hongxi.jaws.zk;

import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.io.InputStream;
import java.util.Properties;

public class ZKStartup {

    public static void main(String[] args) throws Exception {
        QuorumPeerConfig config = new QuorumPeerConfig();
        InputStream is = ZKStartup.class.getResourceAsStream("/zookeeper.properties");
        Properties properties = new Properties();
        properties.load(is);
        config.parseProperties(properties);
        ServerConfig serverconfig = new ServerConfig();
        serverconfig.readFrom(config);
        new ZooKeeperServerMain().runFromConfig(serverconfig);
    }
}