package org.hongxi.jaws.registry.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.AbstractRegistryFactory;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Created by shenhongxi on 2021/4/24.
 */
@SpiMeta(name = "zookeeper")
public class ZookeeperRegistryFactory extends AbstractRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperRegistryFactory.class);

    @Override
    protected Registry createRegistry(URL registryUrl) {
        try {
            int timeout = registryUrl.getParameter(URLParamType.connectTimeout.getName(), URLParamType.connectTimeout.intValue());
            int sessionTimeout = registryUrl.getParameter(URLParamType.registrySessionTimeout.getName(), URLParamType.registrySessionTimeout.intValue());
            String username = registryUrl.getParameter("username");
            String password = registryUrl.getParameter("password");
            CuratorFramework curator = createCurator(registryUrl.getBackupAddress(), username, password,
                    sessionTimeout, timeout);
            return new ZookeeperRegistry(registryUrl, curator);
        } catch (Exception e) {
            log.error("[ZookeeperRegistry] fail to connect zookeeper", e);
            throw new RuntimeException(e);
        }
    }

    protected CuratorFramework createCurator(String zkServers, String username, String password,
                                             int sessionTimeout, int connectionTimeout) {
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(zkServers)
                .sessionTimeoutMs(sessionTimeout)
                .connectionTimeoutMs(connectionTimeout)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3));
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            String auth = username + ":" + password;
            builder.authorization("digest", auth.getBytes(StandardCharsets.UTF_8));
        }
        CuratorFramework curator = builder.build();
        curator.start();
        return curator;
    }


}
