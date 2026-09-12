package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
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
 *   <li>Multi-instance sync: multiple services across nodes</li>
 *   <li>Client-level DELETE sync</li>
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
    // Naming sync tests (client-level granularity)
    // ========================================================================

    @Test
    void testNamingSyncFromNode1ToOthers() {
        // Register a connection on node1 (simulates bi-stream setup)
        node1.getConnectionManager().register("test-conn-1", "10.0.0.1", "3.0.0",
                Map.of(), noopPushSubject());

        // Register an instance on node1
        Instance instance = createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@demo-svc");
        node1.getServiceStorage().registerInstance("public", "DEFAULT_GROUP", "demo-svc",
                instance, "test-conn-1");

        // Trigger client-level distro sync
        syncClientToPeers(node1, "test-conn-1");

        // Verify on node2
        List<Instance> node2Instances = node2.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "demo-svc");
        assertEquals(1, node2Instances.size(), "node2 should have the instance");
        assertEquals("10.0.0.1", node2Instances.get(0).getIp());
        assertEquals(8080, node2Instances.get(0).getPort());

        // Verify on node3
        List<Instance> node3Instances = node3.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "demo-svc");
        assertEquals(1, node3Instances.size(), "node3 should have the instance");
        assertEquals("10.0.0.1", node3Instances.get(0).getIp());
    }

    @Test
    void testNamingSyncFromNode2ToOthers() {
        node2.getConnectionManager().register("test-conn-2", "10.0.0.2", "3.0.0",
                Map.of(), noopPushSubject());

        Instance instance = createInstance("10.0.0.2", 9090, "10.0.0.2#9090#DEFAULT_GROUP@@order-svc");
        node2.getServiceStorage().registerInstance("public", "DEFAULT_GROUP", "order-svc",
                instance, "test-conn-2");

        syncClientToPeers(node2, "test-conn-2");

        // Verify on node1
        List<Instance> node1Instances = node1.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "order-svc");
        assertEquals(1, node1Instances.size());
        assertEquals("10.0.0.2", node1Instances.get(0).getIp());

        // Verify on node3
        List<Instance> node3Instances = node3.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "order-svc");
        assertEquals(1, node3Instances.size());
    }

    @Test
    void testMultipleServicesSyncAcrossCluster() {
        // Register multiple services on different nodes, each with its own connection
        for (int i = 0; i < 3; i++) {
            String connId = "test-conn-multi-" + i;
            HarborServer node = switch (i) {
                case 0 -> node1;
                case 1 -> node2;
                default -> node3;
            };

            node.getConnectionManager().register(connId, "10.0.1." + (i + 1), "3.0.0",
                    Map.of(), noopPushSubject());

            Instance inst = new Instance();
            inst.setIp("10.0.1." + (i + 1));
            inst.setPort(7000 + i);
            inst.setInstanceId("10.0.1." + (i + 1) + "#" + (7000 + i) + "#DEFAULT_GROUP@@multi-svc");

            node.getServiceStorage().registerInstance("public", "DEFAULT_GROUP", "multi-svc",
                    inst, connId);

            syncClientToPeers(node, connId);
        }

        // All 3 nodes should have all 3 instances of "multi-svc"
        for (HarborServer node : List.of(node1, node2, node3)) {
            List<Instance> instances = node.getServiceStorage()
                    .getInstances("public", "DEFAULT_GROUP", "multi-svc");
            assertEquals(3, instances.size(),
                    "Each node should have 3 instances, but " +
                            node.getClusterManager().allMembers() + " has " + instances.size());
        }
    }

    // ========================================================================
    // Client-level DELETE sync
    // ========================================================================

    @Test
    void testClientDeleteSync() {
        // Register a connection and instance on node1
        String connId = "test-conn-delete";
        node1.getConnectionManager().register(connId, "10.0.2.1", "3.0.0",
                Map.of(), noopPushSubject());

        Instance instance = createInstance("10.0.2.1", 6060, "10.0.2.1#6060#DEFAULT_GROUP@@delete-svc");
        node1.getServiceStorage().registerInstance("public", "DEFAULT_GROUP", "delete-svc",
                instance, connId);
        syncClientToPeers(node1, connId);

        // Verify synced to node2
        assertEquals(1, node2.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "delete-svc").size());

        // Now simulate connection close on node1: deregister + Distro DELETE
        node1.getServiceStorage().deregisterInstancesByConnectionId(connId);
        node1.getConnectionManager().remove(connId);
        node1.getDistroProtocol().syncChange(connId, DistroProtocol.OP_DELETE, new byte[0]);

        // Verify removed from node2
        assertEquals(0, node2.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "delete-svc").size(),
                "node2 should have removed the synced client's instances");
    }

    // ========================================================================
    // Verify revision consistency
    // ========================================================================

    @Test
    void testVerifyRevisionConsistency() {
        // After all the sync tests above, the client revisions should be
        // consistent across nodes (synced clients should have the same revision
        // as the source)
        var caches1 = node1.getConnectionManager().allClientSessions();
        var caches2 = node2.getConnectionManager().allClientSessions();

        // Both nodes should have the same number of client sessions
        assertEquals(caches1.size(), caches2.size(),
                "node1 and node2 should have same number of client sessions");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Build ClientSyncData for the given connection and trigger Distro sync.
     */
    private static void syncClientToPeers(HarborServer node, String connId) {
        ClientSyncData syncData = node.getServiceStorage().buildClientSyncData(connId);
        assertNotNull(syncData);
        byte[] content = JSON.toJSONBytes(syncData);
        node.getDistroProtocol().syncChange(connId, DistroProtocol.OP_CHANGE, content);
    }

    private static HarborServer createNode(String host, int port, InMemoryTransport transport) {
        URL url = new URL("harbor", host, port, "");
        return new HarborServer(url, transport);
    }

    private static Instance createInstance(String ip, int port, String instanceId) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setInstanceId(instanceId);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setEphemeral(true);
        instance.setWeight(1.0);
        return instance;
    }

    private static org.hongxi.jaws.transport.StreamSubject<com.google.protobuf.Message> noopPushSubject() {
        return new org.hongxi.jaws.transport.StreamSubject<>() {
            @Override public void onNext(com.google.protobuf.Message item) {}
            @Override public void onError(Throwable throwable) {}
            @Override public void onCompleted() {}
        };
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
        public boolean syncData(String targetAddress, String resourceKey,
                                String operation, byte[] content) {
            DistroProtocol target = nodes.get(targetAddress);
            if (target != null) {
                return target.onSync(resourceKey, operation, content);
            }
            return false;
        }

        @Override
        public List<String> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos) {
            DistroProtocol target = nodes.get(targetAddress);
            if (target != null) {
                return target.onVerify(verifyInfos);
            }
            return List.of();
        }

        @Override
        public byte[] getSnapshot(String targetAddress) {
            DistroProtocol target = nodes.get(targetAddress);
            if (target != null) {
                return target.onSnapshot();
            }
            return null;
        }

        @Override
        public void shutdown() {
            // nothing to close
        }
    }
}
