package org.hongxi.jaws.sample;

import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RefererConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.sample.service.HelloService;
import org.hongxi.jaws.sample.service.HelloServiceImpl;
import org.junit.After;
import org.junit.Before;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class BaseTestCase {

    protected static String application = "jaws-sample";
    protected static String module = "sample";

    protected String localAddress = null;

    protected static String group = "test";

    @Before
    public void setUp() throws Exception {
        InetAddress address = NetUtils.getLocalAddress();
        if (address != null) {
            localAddress = address.getHostAddress();
        }
    }

    @After
    public void tearDown() throws Exception {
    }

    protected static ServiceConfig<HelloService> createServiceConfig() {
        ServiceConfig<HelloService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new HelloServiceImpl());
        serviceConfig.setApplication(application);
        serviceConfig.setModule(module);
        serviceConfig.setCheck("true");
        serviceConfig.setInterface(HelloService.class);
        serviceConfig.setGroup(group);
        serviceConfig.setShareChannel(true);

        return serviceConfig;
    }

    protected static RefererConfig<HelloService> createRefererConfig() {
        RefererConfig<HelloService> refererConfig = new RefererConfig<>();
        refererConfig.setInterface(HelloService.class);
        refererConfig.setApplication(application);
        refererConfig.setModule(module);
        refererConfig.setGroup(group);
        return refererConfig;
    }

    protected static <T> ServiceConfig<T> createServiceConfig(Class<T> clz, T impl) {
        ServiceConfig<T> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(impl);
        serviceConfig.setApplication(application);
        serviceConfig.setModule(module);
        serviceConfig.setCheck("true");
        serviceConfig.setInterface(clz);
        serviceConfig.setGroup(group);
        serviceConfig.setShareChannel(true);
        serviceConfig.setVersion("2.0");

        return serviceConfig;
    }

    protected static <T> ServiceConfig<T> createServiceConfig(Class<T> clz, T impl, String group, String version, ProtocolConfig protocol,
                                                              RegistryConfig registryConfig, String export) {
        ServiceConfig<T> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(impl);
        serviceConfig.setApplication(application);
        serviceConfig.setModule(module);
        serviceConfig.setCheck("true");
        serviceConfig.setInterface(clz);
        serviceConfig.setGroup(group);
        serviceConfig.setShareChannel(true);
        serviceConfig.setVersion(version);
        serviceConfig.setProtocol(protocol);
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.setExport(export);
        return serviceConfig;
    }

    protected static <T> RefererConfig<T> createRefererConfig(Class<T> clz) {
        RefererConfig<T> rc = new RefererConfig<>();
        rc.setInterface(clz);
        rc.setApplication(application);
        rc.setModule(module);
        rc.setGroup(group);
        rc.setRequestTimeout(2000);
        rc.setVersion("2.0");
        return rc;
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
        registryConfig.setName(protocolName);
        registryConfig.setId(registryConfig.getName());
        return registryConfig;
    }

    protected static RegistryConfig createRemoteRegistryConfig(String protocol, String name, String address, int port) {
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setRegProtocol(protocol);
        registryConfig.setName(name);
        registryConfig.setId(registryConfig.getName());
        registryConfig.setAddress(address);
        registryConfig.setPort(port);

        return registryConfig;
    }

    protected static List<ProtocolConfig> getMultiProtocols(String... protocolNames) {
        List<ProtocolConfig> protocols = new ArrayList<>();
        for (String protocol : protocolNames) {
            protocols.add(createProtocolConfig(protocol));
        }
        return protocols;
    }

    protected static List<RegistryConfig> getMultiRegister(String... registerName) {
        List<RegistryConfig> registries = new ArrayList<>();
        for (String register : registerName) {
            RegistryConfig registryConfig = createRegistryConfig(register);
            registries.add(registryConfig);
        }
        return registries;
    }
}