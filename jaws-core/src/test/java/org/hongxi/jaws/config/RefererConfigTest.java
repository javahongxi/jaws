package org.hongxi.jaws.config;

import org.hongxi.jaws.BaseTestCase;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.protocol.example.IWorld;
import org.hongxi.jaws.protocol.example.MockWorld;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class RefererConfigTest extends BaseTestCase {

    private RefererConfig<IWorld> refererConfig = null;
    private ServiceConfig<IWorld> serviceConfig = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        RegistryConfig registryConfig = mockLocalRegistryConfig();

        serviceConfig = mockIWorldServiceConfig();
        serviceConfig.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_INJVM));
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.setExport(JawsConstants.PROTOCOL_INJVM);

        refererConfig = mockIWorldRefererConfig();
        refererConfig.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_INJVM));
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
    public void testGetRef() {
        MockWorld mWorld = new MockWorld();
        serviceConfig.setRef(mWorld);
        serviceConfig.export();

        IWorld ref = refererConfig.getRef();
        assertNotNull(ref);
        assertEquals(refererConfig.getClusterSupports().size(), 1);

        int times = 3;
        for (int i = 0; i < times; i++) {
            ref.world("test");
        }
        assertEquals(times, mWorld.stringCount.get());
        serviceConfig.unexport();

        // destroy
        refererConfig.destroy();
        assertFalse(refererConfig.getInitialized().get());
    }

    @Test
    public void testException() {
        IWorld ref = null;

        // protocol empty
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>();
        refererConfig.setProtocols(protocols);
        try {
            ref = refererConfig.getRef();
            assertTrue(false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("protocol not set correctly"));
        }

        // protocol not exists
        protocols.add(mockProtocolConfig("notExist"));
        try {
            ref = refererConfig.getRef();
            assertTrue(false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Protocol is null"));
        }
        protocols.add(mockProtocolConfig("notExist"));

        // method config wrong
        MethodConfig mConfig = new MethodConfig();
        mConfig.setName("notExist");
        refererConfig.setMethods(mConfig);
        try {
            ref = refererConfig.getRef();
            assertTrue(false);
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("not found method"));
        }
    }

    @Test
    public void testMultiProtocol() {
        List<ProtocolConfig> protocols = getMultiProtocols(JawsConstants.PROTOCOL_INJVM, JawsConstants.PROTOCOL_JAWS);
        refererConfig.setProtocols(protocols);
        IWorld ref = refererConfig.getRef();
        assertNotNull(ref);
        assertEquals(protocols.size(), refererConfig.getClusterSupports().size());

    }

    @Test
    public void testMultiRegitstry() {
        List<RegistryConfig> registries =
                getMultiRegister(JawsConstants.REGISTRY_PROTOCOL_LOCAL, JawsConstants.REGISTRY_PROTOCOL_ZOOKEEPER);
        refererConfig.setRegistries(registries);
        refererConfig.loadRegistryUrls();
        assertEquals(registries.size(), refererConfig.getRegistryUrls().size());
    }
}