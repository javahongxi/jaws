package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Distro protocol components: ClusterManager, DistroProtocol,
 * and their integration with ServiceStorage.
 */
class HarborDistroProtocolTest {

    // ========================================================================
    // ClusterManager tests
    // ========================================================================

    @Test
    void testClusterManagerBasics() {
        ClusterManager mgr = newClusterManager("10.0.0.1", 9848);
        assertTrue(mgr.isEmpty());

        mgr.addMember(new ClusterMember("10.0.0.1:9848"));
        mgr.addMember(new ClusterMember("10.0.0.2:9848"));
        assertEquals(2, mgr.size());

        assertEquals(1, mgr.allMembersExceptSelf().size());
    }

    @Test
    void testClusterMemberParsing() {
        ClusterMember member = new ClusterMember("10.0.0.1:9848");
        assertEquals("10.0.0.1", member.host());
        assertEquals(9848, member.port());
        assertEquals("10.0.0.1:9848", member.address());
    }

    @Test
    void testClusterManagerRemove() {
        ClusterManager mgr = newClusterManager("10.0.0.1", 9848);
        ClusterMember m = new ClusterMember("10.0.0.1:9848");
        mgr.addMember(m);
        assertEquals(1, mgr.size());

        mgr.removeMember(m);
        assertEquals(0, mgr.size());
    }

    // ========================================================================
    // ServiceStorage distro support tests
    // ========================================================================

    @Test
    void testServiceStorageSnapshot() {
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage storage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        // Simulate a registered connection
        connMgr.register("test-conn", "10.0.0.1", "3.0.0", Map.of(), noopPushSubject());

        Instance inst = createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@svc1");
        storage.registerInstance("public", "DEFAULT_GROUP", "svc1", inst, "test-conn");

        Map<String, List<Instance>> snapshot = storage.getAllInstanceData();
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.containsKey("public@@DEFAULT_GROUP@@svc1"));
    }

    @Test
    void testServiceStorageVerifyChecksums() {
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage storage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        connMgr.register("test-conn-1", "10.0.0.1", "3.0.0", Map.of(), noopPushSubject());
        connMgr.register("test-conn-2", "10.0.0.2", "3.0.0", Map.of(), noopPushSubject());

        storage.registerInstance("public", "DEFAULT_GROUP", "svc1",
                createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@svc1"), "test-conn-1");
        storage.registerInstance("public", "DEFAULT_GROUP", "svc1",
                createInstance("10.0.0.2", 8081, "10.0.0.2#8081#DEFAULT_GROUP@@svc1"), "test-conn-2");

        Map<String, Integer> checksums = storage.getVerifyChecksums();
        assertEquals(2, checksums.get("public@@DEFAULT_GROUP@@svc1"));
    }

    @Test
    void testServiceStorageApplySnapshot() {
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage storage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        Instance inst = createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@svc1");
        ClientSyncData syncData = new ClientSyncData(
                "remote-conn-snap",
                List.of("public@@DEFAULT_GROUP@@svc1"),
                List.of(inst),
                List.of(),
                0L
        );
        storage.applySnapshot(List.of(syncData));

        List<Instance> instances = storage.getInstances("public", "DEFAULT_GROUP", "svc1");
        assertEquals(1, instances.size());
        assertEquals("10.0.0.1", instances.get(0).getIp());
    }

    @Test
    void testClientSyncDataBuildAndApply() {
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage storage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        connMgr.register("conn-1", "10.0.0.1", "3.0.0", Map.of(), noopPushSubject());

        storage.registerInstance("public", "DEFAULT_GROUP", "svc1",
                createInstance("10.0.0.1", 8080, "inst1"), "conn-1");
        storage.registerInstance("public", "DEFAULT_GROUP", "svc2",
                createInstance("10.0.0.1", 8081, "inst2"), "conn-1");

        // Build ClientSyncData
        ClientSyncData syncData = storage.buildClientSyncData("conn-1");
        assertNotNull(syncData);
        assertEquals("conn-1", syncData.getClientId());
        assertEquals(2, syncData.getServiceKeys().size());

        // Apply to another storage
        ConnectionManager connMgr2 = new ConnectionManager();
        ServiceStorage storage2 = new ServiceStorage((a, b, c, d, e) -> {}, connMgr2);
        storage2.applyClientSyncData(syncData);

        // Verify data was synced
        assertEquals(1, storage2.getInstances("public", "DEFAULT_GROUP", "svc1").size());
        assertEquals(1, storage2.getInstances("public", "DEFAULT_GROUP", "svc2").size());
    }

    // ========================================================================
    // DistroProtocol tests
    // ========================================================================

    @Test
    void testDistroProtocolStartAndShutdown() {
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);
        HarborNodeTransport transport = noopTransport();
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        DistroProtocol protocol = new DistroProtocol(cluster, transport, svcStorage, connMgr);
        protocol.start();
        assertTrue(protocol.isInitialized());

        protocol.shutdown();
    }

    @Test
    void testDistroProtocolOnReceiveClientChange() {
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);
        HarborNodeTransport transport = noopTransport();
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        DistroProtocol protocol = new DistroProtocol(cluster, transport, svcStorage, connMgr);

        // Build a ClientSyncData simulating a remote client
        Instance instance = createInstance("10.0.0.5", 9090, "inst-remote");
        ClientSyncData syncData = new ClientSyncData(
                "remote-conn-1",
                List.of("public@@DEFAULT_GROUP@@remote-svc"),
                List.of(instance),
                List.of(),
                12345L
        );

        boolean ok = protocol.onSync("remote-conn-1",
                "CHANGE", JSON.toJSONBytes(syncData));
        assertTrue(ok);

        List<Instance> instances = svcStorage.getInstances("public", "DEFAULT_GROUP", "remote-svc");
        assertEquals(1, instances.size());
        assertEquals("10.0.0.5", instances.get(0).getIp());
    }

    @Test
    void testDistroProtocolOnReceiveClientDelete() {
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);
        HarborNodeTransport transport = noopTransport();
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        DistroProtocol protocol = new DistroProtocol(cluster, transport, svcStorage, connMgr);

        // First sync a client
        Instance instance = createInstance("10.0.0.5", 9090, "inst-remote");
        ClientSyncData syncData = new ClientSyncData(
                "remote-conn-1",
                List.of("public@@DEFAULT_GROUP@@remote-svc"),
                List.of(instance),
                List.of(),
                12345L
        );
        protocol.onSync("remote-conn-1", "CHANGE", JSON.toJSONBytes(syncData));
        assertEquals(1, svcStorage.getInstances("public", "DEFAULT_GROUP", "remote-svc").size());

        // Now delete the client
        boolean ok = protocol.onSync("remote-conn-1", "DELETE", new byte[0]);
        assertTrue(ok);
        assertEquals(0, svcStorage.getInstances("public", "DEFAULT_GROUP", "remote-svc").size());
    }

    @Test
    void testDistroProtocolSnapshot() {
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);
        HarborNodeTransport transport = noopTransport();
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);

        connMgr.register("test-conn", "10.0.0.1", "3.0.0", Map.of(), noopPushSubject());
        svcStorage.registerInstance("public", "DEFAULT_GROUP", "svc1",
                createInstance("10.0.0.1", 8080, "inst1"), "test-conn");

        DistroProtocol protocol = new DistroProtocol(cluster, transport, svcStorage, connMgr);

        byte[] namingSnapshot = protocol.onSnapshot();
        assertNotNull(namingSnapshot);
        assertTrue(namingSnapshot.length > 0);
    }

    @Test
    void testDistroSyncToPeers() {
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);
        cluster.addMember(new ClusterMember("10.0.0.1:9848")); // self
        cluster.addMember(new ClusterMember("10.0.0.2:9848")); // peer

        AtomicReference<String> lastSyncTarget = new AtomicReference<>();
        AtomicReference<String> lastSyncKey = new AtomicReference<>();

        HarborNodeTransport mockTransport = new HarborNodeTransport() {
            @Override
            public boolean syncData(String targetAddress, String resourceKey,
                                    String operation, byte[] content) {
                lastSyncTarget.set(targetAddress);
                lastSyncKey.set(resourceKey);
                return true;
            }
            @Override
            public List<String> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos) {
                return List.of();
            }
            @Override
            public byte[] getSnapshot(String targetAddress) {
                return null;
            }
            @Override
            public void shutdown() {}
        };

        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);
        DistroProtocol protocol = new DistroProtocol(cluster, mockTransport, svcStorage, connMgr);

        // Sync with connectionId as resourceKey (client-level granularity)
        protocol.syncChange("conn-123", "CHANGE",
                "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals("10.0.0.2:9848", lastSyncTarget.get());
        assertEquals("conn-123", lastSyncKey.get());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static HarborNodeTransport noopTransport() {
        return new HarborNodeTransport() {
            @Override
            public boolean syncData(String targetAddress, String resourceKey,
                                    String operation, byte[] content) {
                return true;
            }
            @Override
            public List<String> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos) {
                return List.of();
            }
            @Override
            public byte[] getSnapshot(String targetAddress) {
                return null;
            }
            @Override
            public void shutdown() {}
        };
    }

    private static ClusterManager newClusterManager(String host, int port) {
        URL url = new URL("grpc", host, port, "");
        return new ClusterManager(url);
    }

    private static Instance createInstance(String ip, int port, String instanceId) {
        Instance inst = new Instance();
        inst.setIp(ip);
        inst.setPort(port);
        inst.setInstanceId(instanceId);
        return inst;
    }

    private static org.hongxi.jaws.transport.StreamSubject<com.google.protobuf.Message> noopPushSubject() {
        return new org.hongxi.jaws.transport.StreamSubject<>() {
            @Override public void onNext(com.google.protobuf.Message item) {}
            @Override public void onError(Throwable throwable) {}
            @Override public void onCompleted() {}
        };
    }
}
