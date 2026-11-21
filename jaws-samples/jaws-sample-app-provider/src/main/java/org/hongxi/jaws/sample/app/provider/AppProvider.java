package org.hongxi.jaws.sample.app.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.app.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.app.provider.service.OrderServiceImpl;

/**
 * Service provider sample for application-level service discovery
 * (local metadata mode).
 *
 * <pre>
 * Demo scenarios:
 * 1. registerMode=instance — the registry holds ONE lightweight instance per
 *    application (path = application name, parameters carry a revision pointer)
 *    instead of one URL per interface
 * 2. Metadata lives in provider memory (ApplicationMetadataManager); an internal
 *    MetadataService is exported on the same port for consumers to pull it by
 *    revision — watch the "Registered application instance ... revision=..." logs
 * 3. Interface-to-application mappings are written to the registry's
 *    DynamicConfiguration (key "mapping:{interface}"), which is how a plain
 *    consumer reference gets routed to application-level discovery
 * </pre>
 *
 * <p>Make sure Nacos is running at 127.0.0.1:8848 before starting, then run
 * this provider and {@code AppConsumer}. See doc/app-level-discovery.md.</p>
 */
public class AppProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "20020"));

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_NACOS);
        // The only switch: register application instances instead of interface URLs.
        registryConfig.setRegisterMode(JawsConstants.REGISTER_MODE_INSTANCE);

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-app-provider");
        demoServiceConfig.setModule("sample-app");
        demoServiceConfig.setCheck(true);
        demoServiceConfig.setInterface(DemoService.class);
        demoServiceConfig.setProtocol(protocolConfig);
        demoServiceConfig.setRegistry(registryConfig);
        demoServiceConfig.export();
        System.out.println("DemoService exported.");

        /* Export OrderService — same application, the instance's revision is
           recomputed and re-registered, no second instance appears. */
        ServiceConfig<OrderService> orderServiceConfig = new ServiceConfig<>();
        orderServiceConfig.setRef(new OrderServiceImpl());
        orderServiceConfig.setApplication("sample-app-provider");
        orderServiceConfig.setModule("sample-app");
        orderServiceConfig.setInterface(OrderService.class);
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
        registryConfig.setPort(8848);
        return registryConfig;
    }
}