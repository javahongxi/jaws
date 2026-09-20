package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.AbstractRegistryFactory;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Factory to create NacosRegistry instances.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
@Extension(JawsConstants.REGISTRY_PROTOCOL_NACOS)
public class NacosRegistryFactory extends AbstractRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistryFactory.class);

    @Override
    protected Registry createRegistry(URL registryUrl) {
        try {
            String address = registryUrl.getBackupAddress();
            String username = registryUrl.getParameter("username");
            String password = registryUrl.getParameter("password");
            NamingService namingService = NamingFactory.createNamingService(
                    buildProperties(address, username, password));
            return new NacosRegistry(registryUrl, namingService);
        } catch (Exception e) {
            log.error("fail to connect nacos", e);
            throw new RuntimeException(e);
        }
    }

    private Properties buildProperties(String serverAddr, String username, String password) {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        // No connect/request timeout here: naming runs over gRPC, whose serverCheck /
        // keepAlive timeouts come from GrpcClientConfig + system properties
        // (nacos.remote.client.grpc.*), not from NamingService properties.
        if (username != null && !username.isEmpty()) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
        }
        if (password != null && !password.isEmpty()) {
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        return properties;
    }
}
