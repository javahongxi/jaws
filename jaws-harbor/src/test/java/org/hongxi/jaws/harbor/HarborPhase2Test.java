package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.distro.DistroConfig;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.distro.NoopHarborNodeTransport;
import org.hongxi.jaws.harbor.model.Instance;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Phase 2 components: ClusterManager, DistroProtocol,
 * and their integration with ServiceStorage.
 */
class HarborPhase2Test {

    // ========================================================================
    // ClusterManager tests
    // ========================================================================

    @Test
    void testClusterManagerBasics() {
        ClusterManager mgr = new ClusterManager();
        assertTrue(mgr.isEmpty());

        mgr.addMember(new ClusterMember("10.0.0.1:9848"));
        mgr.addMember(new ClusterMember("10.0.0.2:9848"));
        assertEquals(2, mgr.size());

        mgr.setSelfAddress("10.0.0.1:9848");
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
        ClusterManager mgr = new ClusterManager();
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
        ServiceStorage storage = new ServiceStorage((a, b, c, d, e) -> {});

        Instance inst = createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@svc1");
        storage.registerInstance("public", "DEFAULT_GROUP", "svc1", inst, "test-conn");

        Map<String, List<Instance>> snapshot = storage.getAllInstanceData();
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.containsKey("public@@DEFAULT_GROUP@@svc1"));
    }

    @Test
    void testServiceStorageVerifyChecksums() {
        ServiceStorage storage = new ServiceStorage((a, b, c, d, e) -> {});

        storage.registerInstance("public", "DEFAULT_GROUP", "svc1",
                createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@svc1"), "test-conn-1");
        storage.registerInstance("public", "DEFAULT_GROUP", "svc1",
                createInstance("10.0.0.2", 8081, "10.0.0.2#8081#DEFAULT_GROUP@@svc1"), "test-conn-2");

        Map<String, Integer> checksums = storage.getVerifyChecksums();
        assertEquals(2, checksums.get("public@@DEFAULT_GROUP@@svc1"));
    }

    @Test
    void testServiceStorageApplySnapshot() {
        ServiceStorage storage = new ServiceStorage((a, b, c, d, e) -> {});

        Instance inst = createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@svc1");
        Map<String, List<Instance>> snapshot = Map.of(
                "public@@DEFAULT_GROUP@@svc1", List.of(inst)
        );
        storage.applySnapshot(snapshot);

        List<Instance> instances = storage.getInstances("public", "DEFAULT_GROUP", "svc1");
        assertEquals(1, instances.size());
        assertEquals("10.0.0.1", instances.get(0).getIp());
    }

    // ========================================================================
    // DistroProtocol tests
    // ========================================================================

    @Test
    void testDistroProtocolStartAndShutdown() {
        ClusterManager cluster = new ClusterManager();
        DistroConfig config = new DistroConfig();
        NoopHarborNodeTransport transport = new NoopHarborNodeTransport();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {});

        DistroProtocol protocol = new DistroProtocol(cluster, config, transport, svcStorage);
        protocol.start();
        assertTrue(protocol.isInitialized());

        protocol.shutdown();
    }

    @Test
    void testDistroProtocolOnReceiveNaming() {
        ClusterManager cluster = new ClusterManager();
        DistroConfig config = new DistroConfig();
        NoopHarborNodeTransport transport = new NoopHarborNodeTransport();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {});

        DistroProtocol protocol = new DistroProtocol(cluster, config, transport, svcStorage);

        // Simulate receiving a naming sync — content is a serialized Instance
        Instance instance = createInstance("10.0.0.5", 9090, "10.0.0.5#9090#DEFAULT_GROUP@@remote-svc");

        boolean ok = protocol.onReceive("naming", "public@@DEFAULT_GROUP@@remote-svc",
                "CHANGE", JSON.toJSONBytes(instance));
        assertTrue(ok);

        List<Instance> instances = svcStorage.getInstances("public", "DEFAULT_GROUP", "remote-svc");
        assertEquals(1, instances.size());
        assertEquals("10.0.0.5", instances.get(0).getIp());
    }

    @Test
    void testDistroProtocolSnapshot() {
        ClusterManager cluster = new ClusterManager();
        DistroConfig config = new DistroConfig();
        NoopHarborNodeTransport transport = new NoopHarborNodeTransport();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {});

        svcStorage.registerInstance("public", "DEFAULT_GROUP", "svc1",
                createInstance("10.0.0.1", 8080, "10.0.0.1#8080#DEFAULT_GROUP@@svc1"), "test-conn");

        DistroProtocol protocol = new DistroProtocol(cluster, config, transport, svcStorage);

        byte[] namingSnapshot = protocol.onSnapshot("naming");
        assertNotNull(namingSnapshot);
        assertTrue(namingSnapshot.length > 0);
    }

    @Test
    void testDistroSyncToPeers() {
        ClusterManager cluster = new ClusterManager();
        cluster.setSelfAddress("10.0.0.1:9848");
        cluster.addMember(new ClusterMember("10.0.0.1:9848")); // self
        cluster.addMember(new ClusterMember("10.0.0.2:9848")); // peer

        AtomicReference<String> lastSyncTarget = new AtomicReference<>();
        AtomicReference<String> lastSyncType = new AtomicReference<>();

        HarborNodeTransport mockTransport = new HarborNodeTransport() {
            @Override
            public boolean syncData(String targetAddress, String resourceType,
                                    String resourceKey, String operation, byte[] content) {
                lastSyncTarget.set(targetAddress);
                lastSyncType.set(resourceType + ":" + resourceKey);
                return true;
            }
            @Override
            public boolean syncVerify(String targetAddress, String resourceType,
                                      Map<String, String> checksums) {
                return true;
            }
            @Override
            public byte[] getSnapshot(String targetAddress, String resourceType) {
                return null;
            }
            @Override
            public void shutdown() {}
        };

        DistroConfig config = new DistroConfig();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {});

        DistroProtocol protocol = new DistroProtocol(cluster, config, mockTransport, svcStorage);

        protocol.syncNamingChange("public@@DEFAULT_GROUP@@svc1", "CHANGE",
                "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals("10.0.0.2:9848", lastSyncTarget.get());
        assertEquals("naming:public@@DEFAULT_GROUP@@svc1", lastSyncType.get());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static Instance createInstance(String ip, int port, String instanceId) {
        Instance inst = new Instance();
        inst.setIp(ip);
        inst.setPort(port);
        inst.setInstanceId(instanceId);
        return inst;
    }
}
