package org.hongxi.jaws.registry.zookeeper;

import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkException;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.support.AbstractRegistryFactory;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2021/4/24.
 */
@SpiMeta(name = "zookeeper")
public class ZookeeperRegistryFactory extends AbstractRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperRegistryFactory.class);

    @Override
    protected Registry createRegistry(URL registryUrl) {
        try {
            int timeout = registryUrl.getIntParameter(URLParamType.connectTimeout.getName(), URLParamType.connectTimeout.intValue());
            int sessionTimeout = registryUrl.getIntParameter(URLParamType.registrySessionTimeout.getName(), URLParamType.registrySessionTimeout.intValue());
            ZkClient zkClient = createInnerZkClient(registryUrl.getParameter("address"), sessionTimeout, timeout);
            return new ZookeeperRegistry(registryUrl, zkClient);
        } catch (ZkException e) {
            log.error("[ZookeeperRegistry] fail to connect zookeeper", e);
            throw e;
        }
    }

    protected ZkClient createInnerZkClient(String zkServers, int sessionTimeout, int connectionTimeout) {
        return new ZkClient(zkServers, sessionTimeout, connectionTimeout);
    }
}