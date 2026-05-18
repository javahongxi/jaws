package org.hongxi.jaws.config;

import org.hongxi.jaws.BaseTestCase;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.protocol.example.IWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class ServiceConfigTest extends BaseTestCase {

    private ServiceConfig<IWorld> serviceConfig = null;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();
        serviceConfig = mockIWorldServiceConfig();
        serviceConfig.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_INJVM));
        serviceConfig.setRegistry(mockLocalRegistryConfig());
        serviceConfig.setExport(JawsConstants.PROTOCOL_INJVM + ":" + 0);
    }

    @AfterEach
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