package org.hongxi.jaws.sample.multi.registry.consumer;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.RpcContext;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.sample.api.DemoService;

import java.util.List;

/**
 * Multi-registry consumer — demonstrates discovering services from multiple registries.
 * <p>
 * This consumer subscribes to two local registries (matching the provider's configuration)
 * and merges the discovered references. The {@link org.hongxi.jaws.cluster.directory.RegistryDirectory}
 * groups references by registry and deduplicates by identity.
 * <p>
 * Run {@link org.hongxi.jaws.sample.multi.registry.provider.MultiRegistryProvider} first.
 */
public class MultiRegistryConsumer {

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig();

        /* Create two local registry configs (matching the provider) */
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

        /* Reference DemoService from BOTH registries */
        ReferenceConfig<DemoService> demoRef = new ReferenceConfig<>();
        demoRef.setInterface(DemoService.class);
        demoRef.setApplication("sample-multi-registry-consumer");
        demoRef.setGroup("multi-reg");
        demoRef.setVersion("1.0");
        demoRef.setCheck(false);
        demoRef.setProtocol(protocolConfig);
        demoRef.setRegistries(List.of(registry1, registry2));

        DemoService demoService = demoRef.getRef();

        /* Invoke the service */
        System.out.println("=== Multi-Registry Consumer ===");
        System.out.println("Subscribed to 2 local registries, discovering DemoService...");

        String result = demoService.hello("multi-registry-test");
        System.out.println("hello => " + result);

        /* Print the actual server address */
        URL serverUrl = RpcContext.getContext().getServerUrl();
        if (serverUrl != null) {
            System.out.println("Routed to => " + serverUrl.getHost() + ":" + serverUrl.getPort());
        }

        /* Complex parameter invocation */
        var users = demoService.getUsers();
        System.out.println("getUsers => " + users);

        System.out.println("\nMulti-registry discovery succeeded! Service was found across both registries.");

        /* Exit forcibly (Netty non-daemon threads would prevent JVM from exiting) */
        System.exit(0);
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        return protocolConfig;
    }
}
