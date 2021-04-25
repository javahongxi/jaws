package org.hongxi.jaws.sample.consumer;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RefererConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.api.model.User;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public class SampleConsumer {

    public static void main(String[] args) {
        RefererConfig<DemoService> refererConfig = new RefererConfig<>();
        refererConfig.setInterface(DemoService.class);
        refererConfig.setApplication("sample-consumer");
        refererConfig.setModule("sample");
        refererConfig.setGroup("test");
        refererConfig.setRequestTimeout(2000);
        refererConfig.setVersion("2.0");
        refererConfig.setCheck("false");
        refererConfig.setProtocol(createProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        refererConfig.setRegistry(createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER));

        DemoService demoService = refererConfig.getRef();
        String r = demoService.hello("lily");
        System.out.println(r);
        User user = new User();
        user.setName("lily");
        user.setAge(24);
        User newUser = demoService.rename(user, "lucy");
        System.out.println(newUser);
    }

    protected static ProtocolConfig createProtocolConfig(String protocolName) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolName);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setEndpointFactory("jaws");
        return protocolConfig;
    }

    protected static RegistryConfig createRegistryConfig(String protocolName) {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setRegProtocol(protocolName);
        registryConfig.setName("defaultRegistry");
        registryConfig.setId(registryConfig.getName());
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setPort(2181);
        return registryConfig;
    }
}
