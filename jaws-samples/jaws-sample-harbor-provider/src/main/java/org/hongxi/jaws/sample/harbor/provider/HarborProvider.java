package org.hongxi.jaws.sample.harbor.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.harbor.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.harbor.provider.service.OrderServiceImpl;

/**
 * Service provider sample that registers to the standalone HarborServer.
 *
 * <pre>
 * Demo scenarios:
 * 1. jaws protocol + Nacos registry pointing to HarborServer (port 19848)
 * 2. Multi-service export — DemoService + OrderService
 * 3. group/version configuration
 * </pre>
 *
 * <p>Start {@code jaws-sample-harbor} (HarborBootstrap) first, then run this provider.</p>
 */
public class HarborProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "20000"));
    private static final int HARBOR_PORT = Integer.parseInt(System.getProperty("harbor.port", "19848"));

    public static void main(String[] args) {
        // nacos-client gRPC port offset must be 0 so it connects directly to HarborServer
        System.setProperty("nacos.server.grpc.port.offset", "0");

        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_NACOS);

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-harbor-provider");
        demoServiceConfig.setModule("sample-harbor");
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
        orderServiceConfig.setApplication("sample-harbor-provider");
        orderServiceConfig.setModule("sample-harbor");
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
        registryConfig.setAddress("127.0.0.1:19848,127.0.0.1:19849,127.0.0.1:19850");
        registryConfig.setPort(HARBOR_PORT);
        return registryConfig;
    }
}
