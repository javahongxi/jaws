package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.DistroSnapshotStorage;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Distro file snapshot persistence and recovery.
 */
class HarborDistroSnapshotTest {

    @TempDir
    Path tempDir;

    private DistroSnapshotStorage snapshotStorage;

    @BeforeEach
    void setUp() {
        snapshotStorage = new DistroSnapshotStorage(tempDir);
    }

    // ========================================================================
    // DistroSnapshotStorage basic tests
    // ========================================================================

    @Test
    void testSaveAndLoadSnapshot() {
        Instance inst = createInstance("10.0.0.1", 8080, "inst-1");
        ClientSyncData data = new ClientSyncData(
                "conn-1",
                List.of("public@@DEFAULT_GROUP@@svc1"),
                List.of(inst),
                List.of("public@@DEFAULT_GROUP@@svc2"),
                12345L
        );

        snapshotStorage.saveSnapshot(List.of(data));

        List<ClientSyncData> loaded = snapshotStorage.loadSnapshot();
        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        assertEquals("conn-1", loaded.get(0).getClientId());
        assertEquals(1, loaded.get(0).getServiceKeys().size());
        assertEquals("public@@DEFAULT_GROUP@@svc1", loaded.get(0).getServiceKeys().get(0));
        assertEquals(1, loaded.get(0).getInstances().size());
        assertEquals("10.0.0.1", loaded.get(0).getInstances().get(0).getIp());
        assertEquals(8080, loaded.get(0).getInstances().get(0).getPort());
        assertEquals(1, loaded.get(0).getSubscriberKeys().size());
        assertEquals(12345L, loaded.get(0).getRevision());
    }

    @Test
    void testSaveMultipleClients() {
        ClientSyncData data1 = new ClientSyncData(
                "conn-1",
                List.of("public@@DEFAULT_GROUP@@svc1"),
                List.of(createInstance("10.0.0.1", 8080, "i1")),
                List.of(),
                100L
        );
        ClientSyncData data2 = new ClientSyncData(
                "conn-2",
                List.of("public@@DEFAULT_GROUP@@svc2"),
                List.of(createInstance("10.0.0.2", 9090, "i2")),
                List.of("public@@DEFAULT_GROUP@@svc1"),
                200L
        );

        snapshotStorage.saveSnapshot(List.of(data1, data2));

        List<ClientSyncData> loaded = snapshotStorage.loadSnapshot();
        assertNotNull(loaded);
        assertEquals(2, loaded.size());
    }

    @Test
    void testLoadReturnsNullWhenNoFile() {
        List<ClientSyncData> loaded = snapshotStorage.loadSnapshot();
        assertNull(loaded);
    }

    @Test
    void testSaveEmptyListIsNoop() {
        snapshotStorage.saveSnapshot(List.of());
        assertNull(snapshotStorage.loadSnapshot());
    }

    @Test
    void testSaveNullIsNoop() {
        snapshotStorage.saveSnapshot(null);
        assertNull(snapshotStorage.loadSnapshot());
    }

    @Test
    void testAtomicWritePreventsCorruption() throws IOException {
        // Save a valid snapshot first
        ClientSyncData data = new ClientSyncData(
                "conn-1",
                List.of("public@@DEFAULT_GROUP@@svc1"),
                List.of(createInstance("10.0.0.1", 8080, "i1")),
                List.of(),
                100L
        );
        snapshotStorage.saveSnapshot(List.of(data));

        // Verify the snapshot file exists and temp file does not
        Path snapshotFile = tempDir.resolve("naming_snapshot.json");
        Path tempFile = tempDir.resolve("naming_snapshot.json.tmp");
        assertTrue(Files.exists(snapshotFile));
        assertFalse(Files.exists(tempFile));

        // Verify content is valid
        List<ClientSyncData> loaded = snapshotStorage.loadSnapshot();
        assertNotNull(loaded);
        assertEquals(1, loaded.size());
    }

    @Test
    void testOverwriteExistingSnapshot() {
        // Save first version
        ClientSyncData v1 = new ClientSyncData(
                "conn-1", List.of("svc1"),
                List.of(createInstance("10.0.0.1", 8080, "i1")),
                List.of(), 100L
        );
        snapshotStorage.saveSnapshot(List.of(v1));

        // Save second version (overwrites)
        ClientSyncData v2 = new ClientSyncData(
                "conn-2", List.of("svc2"),
                List.of(createInstance("10.0.0.2", 9090, "i2")),
                List.of(), 200L
        );
        snapshotStorage.saveSnapshot(List.of(v2));

        // Load should return v2
        List<ClientSyncData> loaded = snapshotStorage.loadSnapshot();
        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        assertEquals("conn-2", loaded.get(0).getClientId());
    }

    @Test
    void testGetSnapshotPath() {
        Path expected = tempDir.resolve("naming_snapshot.json");
        assertEquals(expected, snapshotStorage.getSnapshotPath());
    }

    // ========================================================================
    // DistroProtocol integration with snapshot storage
    // ========================================================================

    @Test
    void testProtocolSavesSnapshotOnSync() {
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);
        DistroSnapshotStorage storage = new DistroSnapshotStorage(tempDir);

        DistroProtocol protocol = new DistroProtocol(
                cluster, noopTransport(), svcStorage, connMgr, storage);

        // Simulate receiving a sync from a peer
        Instance inst = createInstance("10.0.0.5", 9090, "inst-remote");
        ClientSyncData syncData = new ClientSyncData(
                "remote-conn-1",
                List.of("public@@DEFAULT_GROUP@@remote-svc"),
                List.of(inst),
                List.of(),
                12345L
        );
        protocol.onSync("remote-conn-1", "CHANGE",
                com.alibaba.fastjson2.JSON.toJSONBytes(syncData));

        // Verify snapshot was saved to disk
        List<ClientSyncData> loaded = storage.loadSnapshot();
        assertNotNull(loaded);
        assertFalse(loaded.isEmpty());
    }

    @Test
    void testProtocolLoadsSnapshotOnStart() {
        // Pre-populate a snapshot file
        Instance inst = createInstance("10.0.0.1", 8080, "inst-1");
        ClientSyncData data = new ClientSyncData(
                "recovered-conn",
                List.of("public@@DEFAULT_GROUP@@recovered-svc"),
                List.of(inst),
                List.of(),
                99999L
        );
        DistroSnapshotStorage storage = new DistroSnapshotStorage(tempDir);
        storage.saveSnapshot(List.of(data));

        // Create a new protocol with the same snapshot storage
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);

        DistroProtocol protocol = new DistroProtocol(
                cluster, noopTransport(), svcStorage, connMgr, storage);
        protocol.start();

        try {
            // Verify data was recovered from snapshot
            List<Instance> instances = svcStorage.getInstances(
                    "public", "DEFAULT_GROUP", "recovered-svc");
            assertEquals(1, instances.size());
            assertEquals("10.0.0.1", instances.get(0).getIp());
            assertEquals(8080, instances.get(0).getPort());
        } finally {
            protocol.shutdown();
        }
    }

    @Test
    void testProtocolWorksWithoutSnapshotStorage() {
        // The 4-arg constructor (null snapshot) should work fine
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);

        DistroProtocol protocol = new DistroProtocol(
                cluster, noopTransport(), svcStorage, connMgr);
        protocol.start();

        try {
            // Sync should work without errors
            Instance inst = createInstance("10.0.0.5", 9090, "inst-remote");
            ClientSyncData syncData = new ClientSyncData(
                    "remote-conn-1",
                    List.of("public@@DEFAULT_GROUP@@remote-svc"),
                    List.of(inst),
                    List.of(),
                    12345L
            );
            boolean ok = protocol.onSync("remote-conn-1", "CHANGE",
                    com.alibaba.fastjson2.JSON.toJSONBytes(syncData));
            assertTrue(ok);
            assertEquals(1, svcStorage.getInstances(
                    "public", "DEFAULT_GROUP", "remote-svc").size());
        } finally {
            protocol.shutdown();
        }
    }

    @Test
    void testSnapshotRecoveryWithMultipleClients() {
        // Save snapshot with multiple clients
        ClientSyncData data1 = new ClientSyncData(
                "conn-A",
                List.of("public@@DEFAULT_GROUP@@svcA"),
                List.of(createInstance("10.0.0.1", 8080, "iA")),
                List.of(),
                100L
        );
        ClientSyncData data2 = new ClientSyncData(
                "conn-B",
                List.of("public@@DEFAULT_GROUP@@svcB"),
                List.of(createInstance("10.0.0.2", 9090, "iB")),
                List.of("public@@DEFAULT_GROUP@@svcA"),
                200L
        );
        DistroSnapshotStorage storage = new DistroSnapshotStorage(tempDir);
        storage.saveSnapshot(List.of(data1, data2));

        // Recover
        ConnectionManager connMgr = new ConnectionManager();
        ServiceStorage svcStorage = new ServiceStorage((a, b, c, d, e) -> {}, connMgr);
        ClusterManager cluster = newClusterManager("10.0.0.1", 9848);
        DistroProtocol protocol = new DistroProtocol(
                cluster, noopTransport(), svcStorage, connMgr, storage);
        protocol.start();

        try {
            // Both services should be recovered
            assertEquals(1, svcStorage.getInstances("public", "DEFAULT_GROUP", "svcA").size());
            assertEquals(1, svcStorage.getInstances("public", "DEFAULT_GROUP", "svcB").size());
        } finally {
            protocol.shutdown();
        }
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
        org.hongxi.jaws.rpc.URL url = new org.hongxi.jaws.rpc.URL("grpc", host, port, "");
        return new ClusterManager(url);
    }

    private static Instance createInstance(String ip, int port, String instanceId) {
        Instance inst = new Instance();
        inst.setIp(ip);
        inst.setPort(port);
        inst.setInstanceId(instanceId);
        return inst;
    }
}
