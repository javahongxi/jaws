package org.hongxi.jaws.sample.app3.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.lifecycle.ShutdownHook;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.metadata.ServiceNameMapping;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.app3.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.app3.provider.service.OrderServiceImpl;

/**
 * Application-level service discovery provider sample on the ZooKeeper registry
 * leg — the same local-metadata demo as {@code AppProvider} (nacos leg), with
 * the instance pointer as an ephemeral znode and the mapping as a persistent
 * znode under /jaws/dynamic-config/.
 *
 * <pre>
 * Demo scenarios:
 * 1. registerMode=instance — ONE lightweight instance per application
 *    (path = application name, parameters carry a revision pointer)
 * 2. Metadata lives in provider memory; the internal MetadataService is
 *    exported on the same port — watch the "Registered application instance
 *    ... revision=..." logs
 * 3. Interface-to-application mappings go to ZooKeeper's DynamicConfiguration
 *    (key "mapping:{interface}"), routing plain consumer references to
 *    application-level discovery
 * </pre>
 *
 * <p>Make sure ZooKeeper is running at 127.0.0.1:2181 before starting, then run
 * this provider and {@code App3Consumer}. See doc/app-level-discovery.md.</p>
 */
public class App3Provider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "20022"));

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);
        // The only switch: register application instances instead of interface URLs.
        registryConfig.setRegisterMode(JawsConstants.REGISTER_MODE_INSTANCE);

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-app3-provider");
        demoServiceConfig.setModule("sample-app3");
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
        orderServiceConfig.setApplication("sample-app3-provider");
        orderServiceConfig.setModule("sample-app3");
        orderServiceConfig.setInterface(OrderService.class);
        orderServiceConfig.setProtocol(protocolConfig);
        orderServiceConfig.setRegistry(registryConfig);
        orderServiceConfig.export();
        System.out.println("OrderService exported.");

        /* The mapping is a persistent config entry that unexport deliberately
           leaves behind (Dubbo parity). This sample owns its lifecycle, so on
           shutdown remove both entries — otherwise a later interface-level run
           against the same registry would be silently routed to this dead app.
           Priority 12: after business unexport (10), while the config client is
           still alive (registry close runs at the default 20). */
        ShutdownHook.registerShutdownHook(() -> {
            ServiceNameMapping.removeMapping(DemoService.class.getName());
            ServiceNameMapping.removeMapping(OrderService.class.getName());
        }, 12);
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