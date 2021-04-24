package org.hongxi.jaws.sample;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.*;
import org.hongxi.jaws.sample.service.HelloService;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class RefererConfigTest extends BaseTestCase {

    private RefererConfig<HelloService> refererConfig = null;
    private ServiceConfig<HelloService> serviceConfig = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        RegistryConfig registryConfig = createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);

        serviceConfig = createServiceConfig();
        serviceConfig.setProtocol(createProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.setExport(JawsConstants.PROTOCOL_JAWS);

        refererConfig = createRefererConfig();
        refererConfig.setProtocol(createProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        refererConfig.setRegistry(registryConfig);

        refererConfig.setCheck("false");
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        if (refererConfig != null) {
            refererConfig.destroy();
        }
        if (serviceConfig != null) {
            serviceConfig.unexport();
        }
    }

    @Test
    public void testInvocation() {
        HelloService world = refererConfig.getRef();
        String r = world.world("hello");
        assertEquals("hello", r);
    }
}