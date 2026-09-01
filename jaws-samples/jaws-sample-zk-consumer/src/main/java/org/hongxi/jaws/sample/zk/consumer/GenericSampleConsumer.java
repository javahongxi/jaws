package org.hongxi.jaws.sample.zk.consumer;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.GenericService;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic invocation sample - invokes RPC without depending on the DemoService interface JAR.
 *
 * <pre>
 * Demo scenarios:
 * 1. Invoking via GenericService.$invoke()
 * 2. Primitive parameter (String)
 * 3. POJO parameter represented as a Map (User)
 * 4. Return values are automatically converted to Map / primitives
 * </pre>
 *
 * Run ZkProvider first to make sure the services are exported
 */
public class GenericSampleConsumer {

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig();
        RegistryConfig registryConfig = createRegistryConfig();

        // Generic reference to DemoService - DemoService.class is not required
        ReferenceConfig<GenericService> ref = new ReferenceConfig<>();
        ref.setInterface(GenericService.class);
        ref.setServiceInterface("org.hongxi.jaws.sample.api.DemoService");
        ref.setGeneric(true);
        ref.setApplication("sample-zk-generic-consumer");
        ref.setGroup("test");
        ref.setVersion("2.0");
        ref.setRequestTimeout(3000);
        ref.setCheck(false);
        ref.setProtocol(protocolConfig);
        ref.setRegistry(registryConfig);

        GenericService demoService = ref.getRef();

        // 1. Primitive parameter: hello(String) => String
        System.out.println("--- Generic Invocation of DemoService ---");
        Object r1 = demoService.$invoke("hello",
                new String[]{"java.lang.String"},
                new Object[]{"lily"});
        System.out.println("hello => " + r1);

        // 2. POJO parameter represented as a Map: rename(User, String) => User
        Map<String, Object> user = new HashMap<>();
        user.put("name", "lily");
        user.put("age", 24);

        Object r2 = demoService.$invoke("rename",
                new String[]{"org.hongxi.jaws.sample.api.model.User", "java.lang.String"},
                new Object[]{user, "lucy"});
        System.out.println("rename => " + r2);

        // 3. No-argument call: getUsers() => List<User>
        Object r3 = demoService.$invoke("getUsers",
                new String[]{},
                new Object[]{});
        System.out.println("getUsers => " + r3);

        System.out.println("\nGeneric invocation done");
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

    private static RegistryConfig createRegistryConfig() {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setProtocol(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);
        registryConfig.setId("defaultRegistry");
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}
