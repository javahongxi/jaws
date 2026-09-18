package org.hongxi.jaws.sample.harborx.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.harborx.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.harborx.provider.service.OrderServiceImpl;

/**
 * Provider that registers to HarborServer through the **nacos leg**:
 * {@code jaws-registry-nacos} driving a real {@code nacos-client}, pointed at a
 * harbor port. It is the interop half of the samples — the same registry, seen
 * from the SDK that harbor claims compatibility with.
 *
 * <pre>
 * Demo scenarios:
 * 1. jaws protocol + Nacos registry pointing at HarborServer (port 19848)
 * 2. Multi-service export — DemoService + OrderService
 * 3. group/version configuration
 * </pre>
 *
 * <p>Start {@code ./run-sample.sh harbor-standalone} first. Running this provider
 * together with {@code jaws-sample-harbor-provider} is the mixed-leg check: both
 * register under the same service coordinates, so each leg's consumer must see the
 * other's provider.</p>
 */
public class HarborxProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "20000"));
    private static final int HARBOR_PORT = Integer.parseInt(System.getProperty("harbor.port", "19848"));

    public static void main(String[] args) {
        // nacos-client reads the gRPC port as HTTP port + offset; HarborServer listens
        // on one port, so the offset must be zeroed for this leg.
        System.setProperty("nacos.server.grpc.port.offset", "0");
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_NACOS);

        /* Export DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-harborx-provider");
        demoServiceConfig.setModule("sample-harborx");
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
        orderServiceConfig.setApplication("sample-harborx-provider");
        orderServiceConfig.setModule("sample-harborx");
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
