package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.harbor.distro.DistroConfig;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the 3-node Distro protocol.
 * <p>
 * Starts three HarborServer instances in-process, connected via an
 * {@link InMemoryTransport} that routes sync/verify/snapshot messages
 * directly between the nodes' {@link DistroProtocol} instances without
 * going through gRPC.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Naming sync: register on node1 → visible on node2 and node3</li>
 *   <li>Config sync: publish on node2 → visible on node1 and node3</li>
 *   <li>Multi-instance sync: multiple services across nodes</li>
 * </ul>
 *
 * @author shenhongxi
 */
class HarborDistroClusterTest {

    private static HarborServer node1;
    private static HarborServer node2;
    private static HarborServer node3;
    private static int port1;
    private static int port2;
    private static int port3;

    @BeforeAll
    static void setUp() throws Exception {
        // Find 3 free ports with gap of 2 (each node uses grpcPort + dashboardPort=grpcPort+1)
        // Allocate 6 consecutive ports, use every other one
        ServerSocket[] sockets = new ServerSocket[6];
        try {
            for (int i = 0; i < 6; i++) {
                sockets[i] = new ServerSocket(0);
            }
            port1 = sockets[0].getLocalPort();
            port2 = sockets[2].getLocalPort();
            port3 = sockets[4].getLocalPort();
        } finally {
            for (ServerSocket s : sockets) {
                if (s != null) s.close();
            }
        }

        // Create 3 HarborServer instances with in-memory transport
        InMemoryTransport transport = new InMemoryTransport();

        node1 = createNode("127.0.0.1", port1, transport);
        node2 = createNode("127.0.0.1", port2, transport);
        node3 = createNode("127.0.0.1", port3, transport);

        // Wire the transport: each node's address maps to its DistroProtocol
        transport.addNode("127.0.0.1:" + port1, node1.getDistroProtocol());
        transport.addNode("127.0.0.1:" + port2, node2.getDistroProtocol());
        transport.addNode("127.0.0.1:" + port3, node3.getDistroProtocol());

        // Cross-add cluster members (each node knows about the other two)
        node1.addClusterMember("127.0.0.1:" + port2);
        node1.addClusterMember("127.0.0.1:" + port3);

        node2.addClusterMember("127.0.0.1:" + port1);
        node2.addClusterMember("127.0.0.1:" + port3);

        node3.addClusterMember("127.0.0.1:" + port1);
        node3.addClusterMember("127.0.0.1:" + port2);

        // Start all nodes
        node1.start();
        node2.start();
        node3.start();

        // Let the initial load tasks run (they'll find empty data on all nodes)
        Thread.sleep(1500);
    }

    @AfterAll
    static void tearDown() {
        if (node1 != null) node1.close();
        if (node2 != null) node2.close();
        if (node3 != null) node3.close();
    }

    // ========================================================================
    // Naming sync tests
    // ========================================================================

    @Test
    void testNamingSyncFromNode1ToOthers() {
        // Register an instance on node1
        JSONObject instance = new JSONObject();
        instance.put("ip", "10.0.0.1");
        instance.put("port", 8080);
        instance.put("instanceId", "10.0.0.1#8080#DEFAULT_GROUP@@demo-svc");
        instance.put("healthy", true);
        instance.put("enabled", true);
        instance.put("ephemeral", true);
        instance.put("weight", 1.0);

        node1.getServiceStorage().registerInstance("public", "DEFAULT_GROUP", "demo-svc", instance);

        // Trigger distro sync manually (in production, HarborServer.handleInstanceRequest does this)
        String key = "public@@DEFAULT_GROUP@@demo-svc";
        JSONObject syncBody = new JSONObject();
        syncBody.put("instance", instance);
        node1.getDistroProtocol().syncNamingChange(key, DistroProtocol.OP_CHANGE,
                com.alibaba.fastjson2.JSON.toJSONBytes(syncBody));

        // Verify on node2
        List<JSONObject> node2Instances = node2.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "demo-svc");
        assertEquals(1, node2Instances.size(), "node2 should have the instance");
        assertEquals("10.0.0.1", node2Instances.get(0).getString("ip"));
        assertEquals(8080, node2Instances.get(0).getIntValue("port"));

        // Verify on node3
        List<JSONObject> node3Instances = node3.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "demo-svc");
        assertEquals(1, node3Instances.size(), "node3 should have the instance");
        assertEquals("10.0.0.1", node3Instances.get(0).getString("ip"));
    }

    @Test
    void testNamingSyncFromNode2ToOthers() {
        // Register on node2
        JSONObject instance = new JSONObject();
        instance.put("ip", "10.0.0.2");
        instance.put("port", 9090);
        instance.put("instanceId", "10.0.0.2#9090#DEFAULT_GROUP@@order-svc");
        instance.put("healthy", true);
        instance.put("enabled", true);

        node2.getServiceStorage().registerInstance("public", "DEFAULT_GROUP", "order-svc", instance);

        String key = "public@@DEFAULT_GROUP@@order-svc";
        JSONObject syncBody = new JSONObject();
        syncBody.put("instance", instance);
        node2.getDistroProtocol().syncNamingChange(key, DistroProtocol.OP_CHANGE,
                com.alibaba.fastjson2.JSON.toJSONBytes(syncBody));

        // Verify on node1
        List<JSONObject> node1Instances = node1.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "order-svc");
        assertEquals(1, node1Instances.size());
        assertEquals("10.0.0.2", node1Instances.get(0).getString("ip"));

        // Verify on node3
        List<JSONObject> node3Instances = node3.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "order-svc");
        assertEquals(1, node3Instances.size());
    }

    @Test
    void testMultipleServicesSyncAcrossCluster() {
        // Register multiple services on different nodes
        for (int i = 0; i < 3; i++) {
            JSONObject inst = new JSONObject();
            inst.put("ip", "10.0.1." + (i + 1));
            inst.put("port", 7000 + i);
            inst.put("instanceId", "10.0.1." + (i + 1) + "#" + (7000 + i) + "#DEFAULT_GROUP@@multi-svc");

            HarborServer node = switch (i) {
                case 0 -> node1;
                case 1 -> node2;
                default -> node3;
            };

            node.getServiceStorage().registerInstance("public", "DEFAULT_GROUP", "multi-svc", inst);

            String key = "public@@DEFAULT_GROUP@@multi-svc";
            JSONObject syncBody = new JSONObject();
            syncBody.put("instance", inst);
            node.getDistroProtocol().syncNamingChange(key, DistroProtocol.OP_CHANGE,
                    com.alibaba.fastjson2.JSON.toJSONBytes(syncBody));
        }

        // All 3 nodes should have all 3 instances of "multi-svc"
        for (HarborServer node : List.of(node1, node2, node3)) {
            List<JSONObject> instances = node.getServiceStorage()
                    .getInstances("public", "DEFAULT_GROUP", "multi-svc");
            assertEquals(3, instances.size(),
                    "Each node should have 3 instances, but " +
                            node.getClusterManager().allMembers() + " has " + instances.size());
        }
    }

    // ========================================================================
    // Config sync tests
    // ========================================================================

    @Test
    void testConfigSyncFromNode2ToOthers() {
        // Publish config on node2
        node2.getConfigStorage().publishConfig("public", "app.yaml", "DEFAULT_GROUP",
                "key: value-from-node2", "yaml");

        // Trigger distro sync
        String key = "public@@app.yaml@@DEFAULT_GROUP";
        JSONObject syncData = new JSONObject();
        syncData.put("content", "key: value-from-node2");
        syncData.put("type", "yaml");
        node2.getDistroProtocol().syncConfigChange(key, DistroProtocol.OP_CHANGE,
                com.alibaba.fastjson2.JSON.toJSONBytes(syncData));

        // Verify on node1
        var record1 = node1.getConfigStorage().queryConfig("public", "app.yaml", "DEFAULT_GROUP");
        assertNotNull(record1, "node1 should have the config");
        assertEquals("key: value-from-node2", record1.content());

        // Verify on node3
        var record3 = node3.getConfigStorage().queryConfig("public", "app.yaml", "DEFAULT_GROUP");
        assertNotNull(record3, "node3 should have the config");
        assertEquals("key: value-from-node2", record3.content());
    }

    @Test
    void testConfigSyncUpdate() {
        // Publish initial version on node1
        node1.getConfigStorage().publishConfig("public", "db.properties", "DEFAULT_GROUP",
                "url=jdbc:mysql://localhost", "properties");
        String key = "public@@db.properties@@DEFAULT_GROUP";
        JSONObject syncData = new JSONObject();
        syncData.put("content", "url=jdbc:mysql://localhost");
        syncData.put("type", "properties");
        node1.getDistroProtocol().syncConfigChange(key, DistroProtocol.OP_CHANGE,
                com.alibaba.fastjson2.JSON.toJSONBytes(syncData));

        // Update on node3
        node3.getConfigStorage().publishConfig("public", "db.properties", "DEFAULT_GROUP",
                "url=jdbc:mysql://remote-host", "properties");
        JSONObject syncData2 = new JSONObject();
        syncData2.put("content", "url=jdbc:mysql://remote-host");
        syncData2.put("type", "properties");
        node3.getDistroProtocol().syncConfigChange(key, DistroProtocol.OP_CHANGE,
                com.alibaba.fastjson2.JSON.toJSONBytes(syncData2));

        // All nodes should have the updated value
        for (HarborServer node : List.of(node1, node2, node3)) {
            var record = node.getConfigStorage().queryConfig("public", "db.properties", "DEFAULT_GROUP");
            assertNotNull(record);
            assertEquals("url=jdbc:mysql://remote-host", record.content());
        }
    }

    // ========================================================================
    // Verify checksum consistency
    // ========================================================================

    @Test
    void testVerifyChecksumsConsistency() {
        // After all the above tests, the checksums should be consistent
        // (same number of instances per service key on all nodes)
        Map<String, Integer> checksums1 = node1.getServiceStorage().getVerifyChecksums();
        Map<String, Integer> checksums2 = node2.getServiceStorage().getVerifyChecksums();
        Map<String, Integer> checksums3 = node3.getServiceStorage().getVerifyChecksums();

        // All nodes should have the same set of service keys
        assertEquals(checksums1.keySet(), checksums2.keySet(),
                "node1 and node2 should have same service keys");
        assertEquals(checksums2.keySet(), checksums3.keySet(),
                "node2 and node3 should have same service keys");

        // And the same instance counts
        for (String key : checksums1.keySet()) {
            assertEquals(checksums1.get(key), checksums2.get(key),
                    "Instance count mismatch for " + key + " between node1 and node2");
            assertEquals(checksums2.get(key), checksums3.get(key),
                    "Instance count mismatch for " + key + " between node2 and node3");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static HarborServer createNode(String host, int port, InMemoryTransport transport) {
        URL url = new URL("harbor", host, port, "");
        return new HarborServer(url, transport);
    }

    /**
     * In-memory {@link HarborNodeTransport} that routes Distro messages
     * directly between nodes' {@link DistroProtocol} instances within the
     * same JVM. No gRPC or network involved.
     */
    static class InMemoryTransport implements HarborNodeTransport {

        private final Map<String, DistroProtocol> nodes = new ConcurrentHashMap<>();

        void addNode(String address, DistroProtocol protocol) {
            nodes.put(address, protocol);
        }

        @Override
        public boolean syncData(String targetAddress, String resourceType,
                                String resourceKey, String operation, byte[] content) {
            DistroProtocol target = nodes.get(targetAddress);
            if (target != null) {
                return target.onReceive(resourceType, resourceKey, operation, content);
            }
            return false;
        }

        @Override
        public boolean syncVerify(String targetAddress, String resourceType,
                                  JSONObject checksums) {
            DistroProtocol target = nodes.get(targetAddress);
            if (target != null) {
                return target.onVerify(resourceType, checksums);
            }
            return false;
        }

        @Override
        public byte[] getSnapshot(String targetAddress, String resourceType) {
            DistroProtocol target = nodes.get(targetAddress);
            if (target != null) {
                return target.onSnapshot(resourceType);
            }
            return null;
        }

        @Override
        public void shutdown() {
            // nothing to close
        }
    }
}
