package org.hongxi.jaws.sample.zk.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.zk.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.zk.provider.service.OrderServiceImpl;

/**
 * Service provider sample
 *
 * <pre>
 * Demo scenarios:
 * 1. jaws protocol + ZooKeeper registry
 * 2. Multi-service export - DemoService + OrderService
 * 3. group/version configuration
 * </pre>
 *
 * Make sure ZooKeeper is running at 127.0.0.1:2181 before starting
 */
public class ZkProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-zk-provider");
        demoServiceConfig.setModule("sample-zk");
        demoServiceConfig.setCheck(true);
        demoServiceConfig.setInterface(DemoService.class);
        demoServiceConfig.setGroup("test");
        demoServiceConfig.setVersion("2.0");
        demoServiceConfig.setProtocol(protocolConfig);
        demoServiceConfig.setRegistry(registryConfig);
        demoServiceConfig.export();
        System.out.println("DemoService exported.");

        /* Export OrderService */
        ServiceConfig<OrderService> orderServiceConfig = new ServiceConfig<>();
        orderServiceConfig.setRef(new OrderServiceImpl());
        orderServiceConfig.setApplication("sample-zk-provider");
        orderServiceConfig.setModule("sample-zk");
        orderServiceConfig.setInterface(OrderService.class);
        orderServiceConfig.setGroup("test");
        orderServiceConfig.setVersion("2.0");
        orderServiceConfig.setProtocol(protocolConfig);
        orderServiceConfig.setRegistry(registryConfig);
        orderServiceConfig.export();
        System.out.println("OrderService exported.");
    }

    private static ProtocolConfig createProtocolConfig(String protocolName) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolName);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        protocolConfig.setPort(PORT);
        return protocolConfig;
    }

    private static RegistryConfig createRegistryConfig(String protocolName) {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setProtocol(protocolName);
        registryConfig.setId("defaultRegistry");
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}
