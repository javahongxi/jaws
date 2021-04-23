package org.hongxi.jaws.config;

import org.hongxi.jaws.BaseTestCase;
import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.mock.MockClient;
import org.hongxi.jaws.protocol.example.Hello;
import org.hongxi.jaws.protocol.example.IHello;
import org.hongxi.jaws.rpc.URL;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public class MultiConfigTest extends BaseTestCase {
    ServiceConfig<IHello> serviceConfig1 = null;
    ServiceConfig<IHello> serviceConfig2 = null;
    RefererConfig<IHello> refererConfig1 = null;
    RefererConfig<IHello> refererConfig2 = null;
    RegistryConfig registryConfig = null;
    int port1 = 18080;
    int port2 = 18081;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockClient.urlMap.clear();
        String group = "test-yf";
        String version = "0.1";
        registryConfig = createLocalRegistryConfig("local", "local");
        serviceConfig1 =
                createServiceConfig(IHello.class, new Hello(), group, version, mockProtocolConfig(JawsConstants.PROTOCOL_JAWS),
                        registryConfig, JawsConstants.PROTOCOL_JAWS + ":" + port1);
        serviceConfig2 =
                createServiceConfig(IHello.class, new Hello(), group, version, mockProtocolConfig(JawsConstants.PROTOCOL_JAWS),
                        registryConfig, JawsConstants.PROTOCOL_JAWS + ":" + port2);
        refererConfig1 = createRefererConfig(IHello.class);
        refererConfig1.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        refererConfig1.setVersion(version);
        refererConfig1.setRegistry(registryConfig);

        refererConfig2 = createRefererConfig(IHello.class);
        refererConfig2.setProtocol(mockProtocolConfig(JawsConstants.PROTOCOL_JAWS));
        refererConfig2.setVersion(version);
        refererConfig2.setRegistry(registryConfig);

    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        if (serviceConfig1 != null) {
            serviceConfig1.unexport();
        }
        if (serviceConfig2 != null) {
            serviceConfig2.unexport();
        }
        if (refererConfig1 != null) {
            refererConfig1.destroy();
        }
        if (refererConfig2 != null) {
            refererConfig2.destroy();
        }
    }

    @Test
    public void testMultiService() {
        try {
            serviceConfig1.export();
            serviceConfig2.export();

            IHello hello = refererConfig1.getRef();
            assertNotNull(hello);
            assertEquals(2, MockClient.urlMap.size());

        } catch (Exception e) {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    @Test
    public void testMultiVersion() {


        try {

            serviceConfig1.setVersion("1.0");
            serviceConfig1.setExport(JawsConstants.PROTOCOL_JAWS + ":" + port1);

            serviceConfig1.export();

            serviceConfig2.setVersion("2.0");
            serviceConfig2.setExport(JawsConstants.PROTOCOL_JAWS + ":" + port2);

            serviceConfig2.export();

            refererConfig1.setVersion("1.0");
            IHello hello1 = refererConfig1.getRef();
            validateCall(port1, 3, hello1);

            refererConfig2.setVersion("2.0");
            IHello hello2 = refererConfig2.getRef();
            validateCall(port2, 2, hello2);

        } catch (Exception e) {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    @Test
    public void testMultiGroup() {
        try {
            // 由于需要提供跨group访问rpc的能力，所以不再验证group是否一致。
            serviceConfig1.setGroup("group1");
            serviceConfig1.export();

            refererConfig1.setGroup("group2");
            IHello hello1 = refererConfig1.getRef();
            validateCall(port1, 3, hello1);


            serviceConfig1.unexport();
            refererConfig1.destroy();
            MockClient.urlMap.clear();

            serviceConfig2.setGroup("group2");
            serviceConfig2.export();

            refererConfig2.setGroup("group1");
            IHello hello2 = refererConfig2.getRef();
            validateCall(port2, 3, hello2);

        } catch (Exception e) {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    private void validateCall(int port, int callTimes, IHello hello) {
        for (int i = 0; i < callTimes; i++) {
            hello.hello();
        }
        boolean exist = false;
        for (Map.Entry<URL, AtomicInteger> entry : MockClient.urlMap.entrySet()) {
            if (entry.getKey().getPort() == port) {
                exist = true;
                assertEquals(callTimes, entry.getValue().get());
            }
        }
        assertTrue(exist);
    }
}