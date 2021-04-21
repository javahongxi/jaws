package org.hongxi.jaws.config;

import org.hongxi.jaws.BaseTestCase;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.protocol.example.IWorld;
import org.hongxi.jaws.rpc.URL;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class ServiceConfigTest extends BaseTestCase {

    private ServiceConfig<IWorld> serviceConfig = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        serviceConfig = mockIWorldServiceConfig();
        serviceConfig.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_INJVM));
        serviceConfig.setRegistry(mockLocalRegistryConfig());
        serviceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":" + 0);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        if (serviceConfig != null) {
            serviceConfig.unexport();
        }
    }

    @Test
    public void testExport() {
        serviceConfig.export();

        assertTrue(serviceConfig.getExported().get());
        assertEquals(serviceConfig.getExporters().size(), 1);
        assertEquals(serviceConfig.getRegistryUrls().size(), 1);

    }

    @Test
    public void testExportException() {
        // registry null
        serviceConfig = mockIWorldServiceConfig();
        try {
            serviceConfig.export();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Should set registry"));
        }
        serviceConfig.setRegistry(mockLocalRegistryConfig());

        // export null
        try {
            serviceConfig.export();
            assertTrue(false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("export should not empty"));
        }

        // protocol not exist
        serviceConfig.setProtocol(mockProtocolConfig("notExist"));
        serviceConfig.setExport("notExist" + ":" + 0);
        try {
            serviceConfig.export();
            assertTrue(false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Protocol is null"));
        }

        // service already exist
        serviceConfig.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_INJVM));
        serviceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":" + 0);
        serviceConfig.export();
        assertTrue(serviceConfig.getExported().get());

        ServiceConfig<IWorld> newServiceConfig = mockIWorldServiceConfig();
        newServiceConfig.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_INJVM));
        newServiceConfig.setRegistry(mockLocalRegistryConfig());
        newServiceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":" + 0);
        try {
            newServiceConfig.export();
            assertTrue(false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("for same service"));
        }
    }

    @Test
    public void testMethodConfig() {
        List<MethodConfig> methods = new ArrayList<MethodConfig>();
        MethodConfig mc = new MethodConfig();
        mc.setName("world");
        mc.setRetries(1);
        mc.setArgumentTypes("void");
        mc.setRequestTimeout(123);
        methods.add(mc);

        mc = new MethodConfig();
        mc.setName("worldSleep");
        mc.setRetries(2);
        mc.setArgumentTypes("java.lang.String,int");
        mc.setRequestTimeout(456);
        methods.add(mc);

        serviceConfig.setRetries(10);
        serviceConfig.setMethods(methods);
        serviceConfig.export();
        assertEquals(serviceConfig.getExporters().size(), 1);
        assertEquals(serviceConfig.getRegistryUrls().size(), 1);
        URL serviceUrl = serviceConfig.getExporters().get(0).getUrl();
        assertEquals(
                123,
                serviceUrl.getMethodParameter("world", "void", URLParamType.requestTimeout.getName(),
                        URLParamType.requestTimeout.intValue()).intValue());
        assertEquals(
                456,
                serviceUrl.getMethodParameter("worldSleep", "java.lang.String,int", URLParamType.requestTimeout.getName(),
                        URLParamType.requestTimeout.intValue()).intValue());
        assertEquals(1, serviceUrl.getMethodParameter("world", "void", URLParamType.retries.getName(), URLParamType.retries.intValue())
                .intValue());
        assertEquals(
                2,
                serviceUrl.getMethodParameter("worldSleep", "java.lang.String,int", URLParamType.retries.getName(),
                        URLParamType.retries.intValue()).intValue());

    }

    @Test
    public void testMultiProtocol() {
        serviceConfig.setProtocols(getMultiProtocols(JawsConstants.PROTOCOL_INJVM, JawsConstants.PROTOCOL_JAWS));
        serviceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":" + 0 + "," + JawsConstants.PROTOCOL_JAWS + ":8002");
        serviceConfig.export();
        assertEquals(serviceConfig.getExporters().size(), 2);

    }

    @Test
    public void testMultiRegistry() {
        serviceConfig.setRegistries(getMultiRegister(JawsConstants.REGISTRY_PROTOCOL_LOCAL, JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER));
        serviceConfig.loadRegistryUrls();
        assertEquals(2, serviceConfig.getRegistryUrls().size());
    }

    @Test
    public void testUnexport() {
        testExport();
        serviceConfig.unexport();
        assertFalse(serviceConfig.getExported().get());
        assertEquals(serviceConfig.getExporters().size(), 0);
    }
}