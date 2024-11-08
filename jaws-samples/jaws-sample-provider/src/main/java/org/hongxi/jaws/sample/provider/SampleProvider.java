package org.hongxi.jaws.sample.provider;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.api.DemoService;
import org.hongxi.jaws.sample.provider.service.DemoServiceImpl;
import org.hongxi.jaws.switcher.JawsSwitcherUtils;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public class SampleProvider {

    public static void main(String[] args) {
        ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new DemoServiceImpl());
        serviceConfig.setApplication("sample-provider");
        serviceConfig.setModule("sample");
        serviceConfig.setCheck("true");
        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setGroup("test");
        serviceConfig.setShareChannel(true);
        serviceConfig.setVersion("2.0");
        serviceConfig.setProtocol(createProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        serviceConfig.setRegistry(createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER));
        serviceConfig.setExport(JawsConstants.PROTOCOL_JAWS + ":" + 10000);

        serviceConfig.export();

        JawsSwitcherUtils.setSwitcherValue(JawsConstants.REGISTRY_HEARTBEAT_SWITCHER, true);
    }

    private static ProtocolConfig createProtocolConfig(String protocolName) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolName);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setEndpointFactory("jaws");
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
