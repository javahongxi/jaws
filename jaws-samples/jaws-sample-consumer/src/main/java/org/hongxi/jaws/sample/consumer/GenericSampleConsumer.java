package org.hongxi.jaws.sample.consumer;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.GenericService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 泛化调用示例 — 无需依赖 DemoService 接口 JAR 包即可发起 RPC 调用。
 *
 * <pre>
 * 演示场景：
 * 1. 通过 GenericService.$invoke() 发起调用
 * 2. 基础类型参数（String）
 * 3. POJO 参数用 Map 表示（User）
 * 4. 返回值自动转为 Map / 基础类型
 * </pre>
 *
 * 启动前请先运行 SampleProvider 确保服务已发布
 */
public class GenericSampleConsumer {

    public static void main(String[] args) {
        ProtocolConfig protocolConfig = createProtocolConfig();
        RegistryConfig registryConfig = createRegistryConfig();

        // 泛化引用 DemoService — 不需要 DemoService.class
        ReferenceConfig<GenericService> ref = new ReferenceConfig<>();
        ref.setInterface(GenericService.class);
        ref.setServiceInterface("org.hongxi.jaws.sample.api.DemoService");
        ref.setGeneric(true);
        ref.setApplication("sample-generic-consumer");
        ref.setGroup("test");
        ref.setVersion("2.0");
        ref.setRequestTimeout(3000);
        ref.setCheck("false");
        ref.setProtocol(protocolConfig);
        ref.setRegistry(registryConfig);

        GenericService demoService = ref.getRef();

        // 1. 基础类型参数：hello(String) => String
        System.out.println("--- 泛化调用 DemoService ---");
        Object r1 = demoService.$invoke("hello",
                new String[]{"java.lang.String"},
                new Object[]{"lily"});
        System.out.println("hello => " + r1);

        // 2. POJO 参数用 Map 表示：rename(User, String) => User
        Map<String, Object> user = new HashMap<>();
        user.put("name", "lily");
        user.put("age", 24);

        Object r2 = demoService.$invoke("rename",
                new String[]{"org.hongxi.jaws.sample.api.model.User", "java.lang.String"},
                new Object[]{user, "lucy"});
        System.out.println("rename => " + r2);

        // 3. 无参调用：getUsers() => List<User>
        Object r3 = demoService.$invoke("getUsers",
                new String[]{},
                new Object[]{});
        System.out.println("getUsers => " + r3);

        System.out.println("\n泛化调用完成");
        System.exit(0);
    }

    private static ProtocolConfig createProtocolConfig() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setEndpointFactory("netty");
        protocolConfig.setSerialization("fastjson2");
        return protocolConfig;
    }

    private static RegistryConfig createRegistryConfig() {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setRegProtocol(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);
        registryConfig.setName("defaultRegistry");
        registryConfig.setId(registryConfig.getName());
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}
