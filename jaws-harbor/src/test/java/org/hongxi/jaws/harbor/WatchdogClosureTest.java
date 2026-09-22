package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the connection watchdog (HealthCheckScheduler Phase 1) into performing the
 * FULL client-connection closure transaction — the same one {@code channelInactive}
 * and bi-stream {@code onError}/{@code onCompleted} trigger — not just
 * deregister-instances:
 * <ul>
 *   <li>instances of the dead connection are removed,</li>
 *   <li>its SUBSCRIBER registrations are removed (previously leaked when the TCP
 *       channel never fired {@code channelInactive}, e.g. half-open),</li>
 *   <li>the ClientSession is evicted (previously {@code removeStaleConnections}
 *       left it behind), and</li>
 *   <li>peers receive a Distro DELETE immediately instead of waiting for the
 *       verify cycle to notice the mismatch.</li>
 * </ul>
 * The transaction must also be idempotent (later {@code channelInactive} on the
 * same connection is a no-op) and silent for connections that owned nothing.
 */
class WatchdogClosureTest {

    private static final String ADDR1 = "127.0.0.1:19848";
    private static final String ADDR2 = "127.0.0.1:19849";
    private static final ServiceKey SVC_KEY = ServiceKey.of("public", "DEFAULT_GROUP", "svc");

    private ConnectionManager cm;
    private ServiceStorage storage;
    private Recording transport;
    private DistroProtocol distro;
    private HealthCheckScheduler health;

    /** Counts CHANGE vs DELETE deliveries to the peer. */
    private static final class Recording implements HarborNodeTransport {
        final Map<String, DistroProtocol> nodes = new ConcurrentHashMap<>();
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();

        @Override
        public boolean syncData(String addr, String cid, String op, byte[] content) {
            if (!ADDR2.equals(addr)) {
                return false;
            }
            if (DistroProtocol.OP_DELETE.equals(op)) {
                deletes.incrementAndGet();
            } else {
                changes.incrementAndGet();
            }
            DistroProtocol n = nodes.get(addr);
            if (n != null) {
                n.onSync(cid, op, content);
                return true;
            }
            return false;
        }

        @Override
        public List<String> syncVerify(String addr, List<ClientVerifyInfo> v) {
            DistroProtocol n = nodes.get(addr);
            return n == null ? List.of() : n.onVerify(v);
        }

                @Override
        public boolean syncConfigBroadcast(String targetAddress, org.hongxi.jaws.harbor.model.request.ConfigBroadcastSyncRequest request) {
            return true;
        }

@Override
        public byte[] getSnapshot(String addr) {
            DistroProtocol n = nodes.get(addr);
            return n == null ? null : n.onSnapshot();
        }

        @Override
        public void shutdown() { }
    }

    @BeforeEach
    void setUp() {
        cm = new ConnectionManager();
        storage = new ServiceStorage(cm, key -> { });
        ClusterManager cluster = new ClusterManager(new URL("harbor", "127.0.0.1", 19848, ""));
        cluster.addMember(newClusterMember(ADDR2));
        transport = new Recording();
        distro = new DistroProtocol(cluster, transport, storage, cm);
        health = new HealthCheckScheduler(cm, storage, new ConnectionCleanup(cm, storage, distro));
    }

    private static org.hongxi.jaws.harbor.cluster.ClusterMember newClusterMember(String addr) {
        return new org.hongxi.jaws.harbor.cluster.ClusterMember(addr);
    }

    private static StreamSubject<Message> noop() {
        return new StreamSubject<>() {
            @Override public void onNext(Message item) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
    }

    private static Instance instance(String ip, int port) {
        Instance i = new Instance();
        i.setIp(ip);
        i.setPort(port);
        i.setInstanceId(ip + ":" + port);
        return i;
    }

    /** Backdate a connection's last-activity so the next watchdog sweep treats it as dead. */
    private void makeStale(String connId) {
        ConnectionManager.ConnectionRecord record = cm.allConnections().stream()
                .filter(r -> r.connectionId().equals(connId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such connection: " + connId));
        record.lastActiveTime().addAndGet(-91_000);
    }

    @Test
    void watchdogRunsFullClosureTransaction() throws Exception {
        // "pub" publishes svc; "sub" subscribes to svc.
        cm.register("pub", "10.0.0.1", "3.0.0", Map.of(), noop());
        storage.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8080), "pub");
        cm.register("sub", "10.0.0.2", "3.0.0", Map.of(), noop());
        storage.addSubscriber("public", "DEFAULT_GROUP", "svc", "sub");

        makeStale("pub");
        makeStale("sub");
        health.checkHealth();

        assertTrue(storage.getInstances("public", "DEFAULT_GROUP", "svc").isEmpty(),
                "watchdog must deregister the dead publisher's instances");
        assertTrue(storage.getSubscriberConnections(SVC_KEY).isEmpty(),
                "watchdog must remove the dead connection's SUBSCRIBER registrations "
                        + "(previously leaked until the verify cycle healed it)");
        assertNull(cm.getClientSession("pub"),
                "watchdog must evict the ClientSession, not just the connection record");
        assertNull(cm.getClientSession("sub"));
        assertEquals(1, transport.deletes.get(),
                "losing a client must propagate a single Distro DELETE to peers immediately, "
                        + "not wait for verify");
    }

    @Test
    void closureTransactionIsIdempotent() throws Exception {
        cm.register("pub", "10.0.0.1", "3.0.0", Map.of(), noop());
        storage.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8080), "pub");

        makeStale("pub");
        health.checkHealth();
        // The same sweep may also observe the connection via a second signal
        // (e.g. the bi-stream completing, then a late channelInactive).
        health.checkHealth();

        assertEquals(1, transport.deletes.get(),
                "a repeated closure for the same connection must be a no-op — exactly one DELETE");
    }

    @Test
    void closureOfEmptyConnectionPropagatesNothing() throws Exception {
        cm.register("idle", "10.0.0.9", "3.0.0", Map.of(), noop());

        makeStale("idle");
        health.checkHealth();

        assertEquals(0, transport.deletes.get(),
                "a connection that owned no service data must not emit a Distro DELETE");
        assertEquals(0, transport.changes.get());
        assertNull(cm.getClientSession("idle"));
    }

    @Test
    void syncSnapshotIsCapturedBeforeSessionEviction() throws Exception {
        // The DELETE path derives "did this client own anything?" from
        // buildClientSyncData, which REQUIRES the ClientSession to still exist.
        // This test locks the ordering: snapshot first, then evict.
        cm.register("pub", "10.0.0.1", "3.0.0", Map.of(), noop());
        storage.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "pub");
        ClientSyncData before = storage.buildClientSyncData("pub");
        assertNotNull(before, "precondition: session present → snapshot built");

        storage.deregisterInstancesByConnectionId("pub");
        cm.remove("pub");
        assertNull(storage.buildClientSyncData("pub"),
                "postcondition: once the session is gone the snapshot is null — "
                        + "so any cleanup order that evicts first silently skips the DELETE sync");
    }
}
