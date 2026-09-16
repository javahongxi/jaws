package org.hongxi.jaws.harbor;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: starts HarborServer and uses nacos-client SDK to
 * register and discover services, verifying end-to-end compatibility.
 */
class HarborServerIntegrationTest {

    private static HarborServer harborServer;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        // Use a random available port
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        // Tell nacos-client that the gRPC port offset is 0,
        // so it connects to the same port as the server address
        System.setProperty("nacos.server.grpc.port.offset", "0");

        URL url = new URL("harbor", "0.0.0.0", port, "");
        harborServer = new HarborServer(url);
        harborServer.start();

        // Give the server a moment to bind
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() {
        if (harborServer != null) {
            harborServer.close();
        }
        System.clearProperty("nacos.server.grpc.port.offset");
    }

    @Test
    void testRegisterAndDiscover() throws Exception {
        NamingService naming = createNamingService();

        String serviceName = "test-service";
        String group = "DEFAULT_GROUP";
        String namespace = "public";

        // Register an instance
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(8080);
        instance.setWeight(1.0);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setEphemeral(true);
        instance.setMetadata(Collections.singletonMap("env", "test"));

        naming.registerInstance(serviceName, group, instance);

        // Wait for registration to reach the client's view (bounded poll, window-independent).
        awaitTrue(() -> !tryDiscover(naming, serviceName, group).isEmpty(), 5_000);

        // Query instances
        List<Instance> instances = naming.getAllInstances(serviceName, group);
        assertNotNull(instances);
        assertEquals(1, instances.size());

        Instance discovered = instances.get(0);
        assertEquals("127.0.0.1", discovered.getIp());
        assertEquals(8080, discovered.getPort());
        assertTrue(discovered.isHealthy());
        assertEquals(1.0, discovered.getWeight());

        // Deregister
        naming.deregisterInstance(serviceName, group, instance);

        // Wait for the removal to reach the client's view (bounded poll).
        awaitTrue(() -> tryDiscover(naming, serviceName, group).isEmpty(), 5_000);

        // Verify deregistration
        List<Instance> afterDeregister = naming.getAllInstances(serviceName, group);
        assertTrue(afterDeregister.isEmpty());
    }

    @Test
    void testMultipleInstances() throws Exception {
        NamingService naming = createNamingService();

        String serviceName = "multi-instance-service";
        String group = "DEFAULT_GROUP";

        // Register two instances
        Instance inst1 = new Instance();
        inst1.setIp("10.0.0.1");
        inst1.setPort(8080);
        inst1.setEphemeral(true);

        Instance inst2 = new Instance();
        inst2.setIp("10.0.0.2");
        inst2.setPort(8081);
        inst2.setEphemeral(true);

        naming.registerInstance(serviceName, group, inst1);
        naming.registerInstance(serviceName, group, inst2);

        // Wait for both registrations to surface (bounded poll, window-independent).
        awaitTrue(() -> tryDiscover(naming, serviceName, group).size() >= 2, 5_000);

        List<Instance> instances = naming.getAllInstances(serviceName, group);
        assertEquals(2, instances.size());

        // Cleanup
        naming.deregisterInstance(serviceName, group, inst1);
        naming.deregisterInstance(serviceName, group, inst2);
    }

    /**
     * Poll {@code condition} until true or {@code timeoutMs} elapses. Replaces a
     * fixed sleep tied to the push/sync windows: the assertions key on the
     * client's view reaching the expected state instead.
     */
    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("condition not satisfied within " + timeoutMs + "ms");
    }

    /** Discover without throwing: a checked client error simply reads as "not there yet". */
    private static List<Instance> tryDiscover(NamingService naming, String serviceName, String group) {
        try {
            return naming.getAllInstances(serviceName, group);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static NamingService createNamingService() throws Exception {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + port);
        return NacosFactory.createNamingService(props);
    }
}
