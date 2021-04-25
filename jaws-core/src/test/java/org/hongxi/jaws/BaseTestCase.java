package org.hongxi.jaws;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.util.NetUtils;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RefererConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.protocol.example.IWorld;
import org.hongxi.jaws.protocol.example.World;
import org.junit.After;
import org.junit.Before;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class BaseTestCase {

    protected static String application = "api";
    protected static String module = "test";

    protected String localAddress = null;

    protected static String group = "test-2021";

    @Before
    public void setUp() throws Exception {
        InetAddress address = NetUtils.getLocalAddress();
        if (address != null) {
            localAddress = address.getHostAddress();
        }
    }

    @After
    public void tearDown() throws Exception {}

    protected static ServiceConfig<IWorld> mockIWorldServiceConfig() {
        ServiceConfig<IWorld> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new World());
        serviceConfig.setApplication(application);
        serviceConfig.setModule(module);
        serviceConfig.setCheck("true");
        serviceConfig.setInterface(IWorld.class);
        serviceConfig.setGroup(group);
        serviceConfig.setShareChannel(true);

        return serviceConfig;
    }

    protected static RefererConfig<IWorld> mockIWorldRefererConfig() {
        RefererConfig<IWorld> rc = new RefererConfig<IWorld>();
        rc.setInterface(IWorld.class);
        rc.setApplication(application);
        rc.setModule(module);
        rc.setGroup(group);
        return rc;
    }

    protected static <T> ServiceConfig<T> createServiceConfig(Class<T> clz, T impl) {
        ServiceConfig<T> serviceConfig = new MockServiceConfig<T>();
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

    protected static <T> ServiceConfig<T> createServiceConfig(Class<T> clz, T impl, String group, String version, ProtocolConfig protocl,
                                                              RegistryConfig registryConfig, String export) {
        ServiceConfig<T> serviceConfig = new MockServiceConfig<T>();
        serviceConfig.setRef(impl);
        serviceConfig.setApplication(application);
        serviceConfig.setModule(module);
        serviceConfig.setCheck("true");
        serviceConfig.setInterface(clz);
        serviceConfig.setGroup(group);
        serviceConfig.setShareChannel(true);
        serviceConfig.setVersion(version);
        serviceConfig.setProtocol(protocl);
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.setExport(export);
        return serviceConfig;
    }

    protected static <T> RefererConfig<T> createRefererConfig(Class<T> clz) {
        RefererConfig<T> rc = new RefererConfig<T>();
        rc.setInterface(clz);
        rc.setApplication(application);
        rc.setModule(module);
        rc.setGroup(group);
        rc.setRequestTimeout(2000);
        rc.setVersion("2.0");
        return rc;
    }

    protected static ProtocolConfig mockProtocolConfig(String protocolName) {
        ProtocolConfig pc = createProtocol(protocolName);
        pc.setEndpointFactory("mockEndpoint");
        return pc;
    }

    protected static ProtocolConfig createProtocol(String protocolName) {
        ProtocolConfig pc = new ProtocolConfig();
        pc.setName(protocolName);
        pc.setId(pc.getName());
        return pc;
    }

    protected static RegistryConfig mockLocalRegistryConfig() {
        return createLocalRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_LOCAL, JawsConstants.REGISTRY_PROTOCOL_LOCAL);
    }

    protected static RegistryConfig createLocalRegistryConfig(String protocol, String name) {
        RegistryConfig rc = new RegistryConfig();
        rc.setRegProtocol(protocol);
        rc.setName(name);
        rc.setId(rc.getName());

        return rc;
    }

    protected static RegistryConfig createRemoteRegistryConfig(String protocol, String name, String address, int port) {
        RegistryConfig rc = new RegistryConfig();
        rc.setRegProtocol(protocol);
        rc.setName(name);
        rc.setId(rc.getName());
        rc.setAddress(address);
        rc.setPort(port);

        return rc;
    }

    protected static List<ProtocolConfig> getMultiProtocols(String... protocolNames) {
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>();
        for (String protocol : protocolNames) {
            protocols.add(mockProtocolConfig(protocol));
        }
        return protocols;
    }

    protected static List<RegistryConfig> getMultiRegister(String... registerName) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>();
        for (String register : registerName) {
            RegistryConfig registryConfig = createLocalRegistryConfig(register, register);
            registries.add(registryConfig);
        }
        return registries;
    }
}