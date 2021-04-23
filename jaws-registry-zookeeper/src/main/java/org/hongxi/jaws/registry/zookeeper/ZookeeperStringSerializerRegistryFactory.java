package org.hongxi.jaws.registry.zookeeper;

import org.I0Itec.zkclient.ZkClient;
import org.hongxi.jaws.common.extension.SpiMeta;

/**
 * Created by shenhongxi on 2021/4/24.
 */
@SpiMeta(name = "zk")
public class ZookeeperStringSerializerRegistryFactory extends ZookeeperRegistryFactory {
    @Override
    protected ZkClient createInnerZkClient(String zkServers, int sessionTimeout, int connectionTimeout) {
        return new ZkClient(zkServers, sessionTimeout, connectionTimeout, new StringSerializer());
    }
}