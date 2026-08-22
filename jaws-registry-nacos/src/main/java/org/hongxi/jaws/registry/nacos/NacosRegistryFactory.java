package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.hongxi.jaws.common.URLParamType;
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
@Extension("nacos")
public class NacosRegistryFactory extends AbstractRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistryFactory.class);

    @Override
    protected Registry createRegistry(URL registryUrl) {
        try {
            String address = registryUrl.getBackupAddress();
            String username = registryUrl.getParameter("username");
            String password = registryUrl.getParameter("password");
            int connectTimeout = registryUrl.getParameter(URLParamType.connectTimeout.getName(),
                    URLParamType.connectTimeout.intValue());
            NamingService namingService = NamingFactory.createNamingService(
                    buildProperties(address, username, password, connectTimeout));
            return new NacosRegistry(registryUrl, namingService);
        } catch (Exception e) {
            log.error("[NacosRegistry] fail to connect nacos", e);
            throw new RuntimeException(e);
        }
    }

    private Properties buildProperties(String serverAddr, String username, String password, int connectTimeout) {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT, String.valueOf(connectTimeout));
        if (username != null && !username.isEmpty()) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
        }
        if (password != null && !password.isEmpty()) {
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        return properties;
    }


}
