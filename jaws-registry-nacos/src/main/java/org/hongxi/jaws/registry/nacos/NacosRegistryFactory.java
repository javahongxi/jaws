package org.hongxi.jaws.registry.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.support.AbstractRegistryFactory;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Factory to create NacosRegistry instances.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
@SpiMeta(name = "nacos")
public class NacosRegistryFactory extends AbstractRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistryFactory.class);

    @Override
    protected Registry createRegistry(URL registryUrl) {
        try {
            String address = registryUrl.getParameter("address");
            int connectTimeout = registryUrl.getIntParameter(URLParamType.connectTimeout.getName(),
                    URLParamType.connectTimeout.intValue());
            NamingService namingService = createNamingService(address, connectTimeout);
            ConfigService configService = createConfigService(address, connectTimeout);
            return new NacosRegistry(registryUrl, namingService, configService);
        } catch (Exception e) {
            log.error("[NacosRegistry] fail to connect nacos", e);
            throw new RuntimeException(e);
        }
    }

    protected NamingService createNamingService(String serverAddr, int connectTimeout) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT, String.valueOf(connectTimeout));
        return NamingFactory.createNamingService(properties);
    }

    protected ConfigService createConfigService(String serverAddr, int connectTimeout) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.CONFIG_LONG_POLL_TIMEOUT, String.valueOf(connectTimeout));
        return ConfigFactory.createConfigService(properties);
    }
}
