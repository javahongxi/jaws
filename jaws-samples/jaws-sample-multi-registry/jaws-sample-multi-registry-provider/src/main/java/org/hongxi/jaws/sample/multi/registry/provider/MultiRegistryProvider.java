package org.hongxi.jaws.sample.multi.registry.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.multi.registry.provider.service.MultiRegistryDemoServiceImpl;

import java.util.List;

/**
 * Multi-registry provider — demonstrates registering a service to multiple registries.
 * <p>
 * This sample uses two local registry instances to verify the multi-registry code path
 * within a single JVM. In production, you would use real registries such as:
 * <pre>
 * // Nacos + ZooKeeper example:
 * RegistryConfig nacos = new RegistryConfig();
 * nacos.setAddress("nacos://127.0.0.1:8848");
 *
 * RegistryConfig zookeeper = new RegistryConfig();
 * zookeeper.setAddress("zookeeper://127.0.0.1:2181");
 *
 * serviceConfig.setRegistries(List.of(nacos, zookeeper));
 * </pre>
 * <p>
 * The framework's {@link ServiceConfig#export()} iterates over all configured registries
 * and registers the service to each one. Consumers can then discover the service from
 * any of the registries.
 */
public class MultiRegistryProvider {

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Create two local registry configs (for single-JVM demo) */
        RegistryConfig registry1 = new RegistryConfig();
        registry1.setId("localRegistry1");
        registry1.setProtocol(JawsConstants.REGISTRY_PROTOCOL_LOCAL);
        registry1.setAddress("127.0.0.1");
        registry1.setPort(0);

        RegistryConfig registry2 = new RegistryConfig();
        registry2.setId("localRegistry2");
        registry2.setProtocol(JawsConstants.REGISTRY_PROTOCOL_LOCAL);
        registry2.setAddress("127.0.0.2");
        registry2.setPort(0);

        /* Export DemoService to BOTH registries */
        ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new MultiRegistryDemoServiceImpl());
        serviceConfig.setApplication("sample-multi-registry-provider");
        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setGroup("multi-reg");
        serviceConfig.setVersion("1.0");
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.setRegistries(List.of(registry1, registry2));
        serviceConfig.export();

        System.out.println("=== Multi-Registry Provider ===");
        System.out.println("DemoService exported to 2 local registries:");
        System.out.println("  Registry 1: local://127.0.0.1:0");
        System.out.println("  Registry 2: local://127.0.0.2:0");
        System.out.println();
        System.out.println("The service is registered to BOTH registries simultaneously.");
        System.out.println("Run MultiRegistryConsumer to verify discovery from both registries.");
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        protocolConfig.setPort(10000);
        return protocolConfig;
    }
}
