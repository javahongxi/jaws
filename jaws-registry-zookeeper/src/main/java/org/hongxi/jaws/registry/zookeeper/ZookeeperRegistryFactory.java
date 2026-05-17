package org.hongxi.jaws.registry.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
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
            CuratorFramework curator = createCurator(registryUrl.getParameter("address"), sessionTimeout, timeout);
            return new ZookeeperRegistry(registryUrl, curator);
        } catch (Exception e) {
            log.error("[ZookeeperRegistry] fail to connect zookeeper", e);
            throw new RuntimeException(e);
        }
    }

    protected CuratorFramework createCurator(String zkServers, int sessionTimeout, int connectionTimeout) {
        CuratorFramework curator = CuratorFrameworkFactory.builder()
                .connectString(zkServers)
                .sessionTimeoutMs(sessionTimeout)
                .connectionTimeoutMs(connectionTimeout)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .build();
        curator.start();
        return curator;
    }
}
