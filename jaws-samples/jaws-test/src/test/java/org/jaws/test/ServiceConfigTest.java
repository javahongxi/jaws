package org.jaws.test;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ServiceConfig;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class ServiceConfigTest extends BaseTestCase {

    private ServiceConfig<HelloService> serviceConfig = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        serviceConfig = createServiceConfig();
        serviceConfig.setProtocol(createProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        serviceConfig.setRegistry(createRegistryConfig(JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER));
        serviceConfig.setExport(JawsConstants.PROTOCOL_JAWS + ":" + 10001);
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
    public void testUnexport() {
        testExport();
        serviceConfig.unexport();
        assertFalse(serviceConfig.getExported().get());
        assertEquals(serviceConfig.getExporters().size(), 0);
    }
}