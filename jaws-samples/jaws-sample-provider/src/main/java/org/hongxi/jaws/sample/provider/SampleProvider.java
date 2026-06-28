package org.hongxi.jaws.sample.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.OrderService;
import org.hongxi.jaws.sample.provider.service.DemoServiceImpl;
import org.hongxi.jaws.sample.provider.service.OrderServiceImpl;
import org.hongxi.jaws.switcher.JawsSwitcherUtils;

/**
 * 服务提供者示例
 *
 * <pre>
 * 演示场景：
 * 1. jaws 协议 + ZooKeeper 注册中心
 * 2. 多服务导出 - DemoService + OrderService
 * 3. group/version/shareChannel 配置
 * </pre>
 *
 * 启动前请确保 ZooKeeper 已在 127.0.0.1:2181 运行
 */
public class SampleProvider {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "10000"));

    public static void main(String[] args) throws Exception {
        ProtocolConfig protocolConfig = createProtocolConfig(JawsConstants.PROTOCOL_JAWS);
        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);

        /* 导出 DemoService */
        ServiceConfig<DemoService> demoServiceConfig = new ServiceConfig<>();
        demoServiceConfig.setRef(new DemoServiceImpl());
        demoServiceConfig.setApplication("sample-provider");
        demoServiceConfig.setModule("sample");
        demoServiceConfig.setCheck("true");
        demoServiceConfig.setInterface(DemoService.class);
        demoServiceConfig.setGroup("test");
        demoServiceConfig.setVersion("2.0");
        demoServiceConfig.setProtocol(protocolConfig);
        demoServiceConfig.setRegistry(registryConfig);
        demoServiceConfig.setExport(JawsConstants.PROTOCOL_JAWS + ":" + PORT);
        demoServiceConfig.export();
        System.out.println("DemoService exported.");

        /* 导出 OrderService */
        ServiceConfig<OrderService> orderServiceConfig = new ServiceConfig<>();
        orderServiceConfig.setRef(new OrderServiceImpl());
        orderServiceConfig.setApplication("sample-provider");
        orderServiceConfig.setModule("sample");
        orderServiceConfig.setInterface(OrderService.class);
        orderServiceConfig.setGroup("test");
        orderServiceConfig.setVersion("2.0");
        orderServiceConfig.setProtocol(protocolConfig);
        orderServiceConfig.setRegistry(registryConfig);
        orderServiceConfig.setExport(JawsConstants.PROTOCOL_JAWS + ":" + PORT);
        orderServiceConfig.export();
        System.out.println("OrderService exported.");

        JawsSwitcherUtils.setSwitcherValue(JawsConstants.REGISTRY_HEARTBEAT_SWITCHER, true);
    }

    private static ProtocolConfig createProtocolConfig(String protocolName) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolName);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setEndpointFactory("jaws");
        protocolConfig.setSerialization("fastjson2");
        return protocolConfig;
    }

    private static RegistryConfig createRegistryConfig(String protocolName) {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setRegProtocol(protocolName);
        registryConfig.setName("defaultRegistry");
        registryConfig.setId(registryConfig.getName());
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}
