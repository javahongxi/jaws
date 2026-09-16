package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks who may JUDGE an ephemeral instance's health once its data is replicated:
 * only the node holding the client's connection.
 * <p>
 * Health is reconciled against connection liveness (Nacos 2.x model): a node judges
 * health only for connections it holds ({@code ConnectionRecord}s exist only for
 * native clients). A Distro replica holds no connection, so {@code reconcileHealth}
 * never touches its copy — it learns the owner's verdict as replicated data instead.
 * Judging a replica locally would invent an outage the owner never saw.
 * <p>
 * Consequently a health transition is REPLICATED CONTENT: it must bump the session
 * revision (so anti-entropy repairs a lost push) and trigger a coalesced sync (so
 * subscribers converge without waiting for a verify cycle). Reclaiming a replica whose
 * owner went fully silent is a separate concern ({@code reapStaleSyncedClients},
 * covered by {@link SyncedSessionReclamationTest}).
 */
class SyncedHealthAuthorityTest {

    private static final String ADDR1 = "127.0.0.1:19848";
    private static final String ADDR2 = "127.0.0.1:19849";
    private static final long UNHEALTHY_MS = 15_000;

    private Recording transport;
    private ConnectionManager cm1, cm2;
    private ServiceStorage st1, st2;
    private DistroProtocol d1, d2;
    private HealthCheckManager health1, health2;
    private final List<String> notified2 = new CopyOnWriteArrayList<>();

    /** Delivers to the peer as the real transport does, counting CHANGE pushes. */
    private static final class Recording implements HarborNodeTransport {
        final Map<String, DistroProtocol> nodes = new ConcurrentHashMap<>();
        final AtomicInteger changes = new AtomicInteger();

        @Override
        public boolean syncData(String addr, String cid, String op, byte[] content) {
            DistroProtocol n = nodes.get(addr);
            if (n == null) {
                return false;
            }
            boolean delivered = n.onSync(cid, op, content);
            if (delivered && DistroProtocol.OP_CHANGE.equals(op)) {
                changes.incrementAndGet();
            }
            return delivered;
        }

        @Override
        public List<String> syncVerify(String addr, List<ClientVerifyInfo> v) {
            DistroProtocol n = nodes.get(addr);
            return n == null ? List.of() : n.onVerify(v);
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
        transport = new Recording();
        cm1 = new ConnectionManager();
        // Same wiring HarborServer uses: a health verdict judged locally must go
        // out through the coalesced Distro sync path.
        st1 = new ServiceStorage(cm1, key -> { }, connectionId -> d1.requestSyncChange(connectionId));
        cm2 = new ConnectionManager();
        st2 = new ServiceStorage(cm2, service -> notified2.add(service.toKeyString()));

        ClusterManager c1 = new ClusterManager(new URL("harbor", "127.0.0.1", 19848, ""));
        c1.addMember(new ClusterMember(ADDR2));
        ClusterManager c2 = new ClusterManager(new URL("harbor", "127.0.0.1", 19849, ""));
        c2.addMember(new ClusterMember(ADDR1));

        d1 = new DistroProtocol(c1, transport, st1, cm1);
        d2 = new DistroProtocol(c2, transport, st2, cm2);
        transport.nodes.put(ADDR1, d1);
        transport.nodes.put(ADDR2, d2);

        health1 = new HealthCheckManager(cm1, st1, new ConnectionCleanup(cm1, st1, d1));
        health2 = new HealthCheckManager(cm2, st2, new ConnectionCleanup(cm2, st2, d2));

        d1.start();
        d2.start();
        settlePastInitialLoad();

        // node1 owns the connection; node2 carries only the replicated copy.
        cm1.register("pub", "10.0.0.1", "3.0.0", Map.of(), noop());
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8080), "pub");
        pushOwnerStateToPeer();
        notified2.clear();
    }

    /**
     * The one-shot Distro load runs 1s after start and would otherwise be able to
     * fill node2's replica from a snapshot mid-test, pretending to be the push
     * under test. Register only after it has fired against empty data.
     */
    private static void settlePastInitialLoad() {
        try {
            Thread.sleep(1_300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        d1.shutdown();
        d2.shutdown();
    }

    private static Instance instance(String ip, int port) {
        Instance i = new Instance();
        i.setIp(ip);
        i.setPort(port);
        i.setInstanceId(ip + ":" + port);
        return i;
    }

    private static StreamSubject<Message> noop() {
        return new StreamSubject<>() {
            @Override public void onNext(Message item) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
    }

    /** Coalesced outbound sync + JSON round trip, exactly as a running cluster does it. */
    private void pushOwnerStateToPeer() {
        int before = transport.changes.get();
        d1.requestSyncChange("pub");
        awaitChanges(before + 1);
        assertEquals(1, st2.getInstances("public", "DEFAULT_GROUP", "svc").size(),
                "precondition: node2 must be carrying the replicated instance");
    }

    /**
     * Wait for delivery, with a budget deliberately far below the 5s verify cycle:
     * a transition that only shows up after verify has run would pass a weaker
     * assertion while proving nothing about the push path.
     */
    private void awaitChanges(int target) {
        long deadline = System.currentTimeMillis() + 1_500;
        while (System.currentTimeMillis() < deadline && transport.changes.get() < target) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(transport.changes.get() >= target,
                "expected at least " + target + " delivered CHANGE pushes within 1.5s, saw "
                        + transport.changes.get() + " — anything arriving later is the 5s "
                        + "verify repair, not the push under test");
    }

    private Instance ownerInstance() {
        return st1.getInstances("public", "DEFAULT_GROUP", "svc").get(0);
    }

    private Instance replicaInstance() {
        List<Instance> found = st2.getInstances("public", "DEFAULT_GROUP", "svc");
        assertEquals(1, found.size(), "node2 must be carrying exactly the replicated instance");
        return found.get(0);
    }

    /** Age node1's owning connection so the owner (and only the owner) judges it stale. */
    private void ageOwnerConnection(long millis) {
        cm1.allConnections().stream()
                .filter(r -> r.connectionId().equals("pub"))
                .findFirst().orElseThrow().lastActiveTime().addAndGet(-millis);
    }

    // ========================================================================
    // The unhealthy tier: judged by the owner alone (only natives have a record).
    // ========================================================================

    @Test
    void replicaMustNotInventAnOutageTheOwnerNeverSaw() {
        // node2 holds no connection for "pub" → its sweep must not judge the replica.
        assertTrue(replicaInstance().isHealthy(), "precondition: the replica arrives healthy");

        health2.checkHealth();

        assertTrue(replicaInstance().isHealthy(),
                "a node that holds no connection for this client must not judge its health — "
                        + "the owner is alive and its connection active");
    }

    // ========================================================================
    // Health transitions are data: revision + coalesced push, both directions.
    // ========================================================================

    @Test
    void ownerVerdictIsPushedAndCarriesTheRevision() {
        ClientSession session1 = cm1.getClientSession("pub");
        long revisionWhileHealthy = session1.getRevision();
        int before = transport.changes.get();

        // The owner's OWN connection really did go idle — this is the node allowed to judge.
        ageOwnerConnection(UNHEALTHY_MS + 5_000);
        health1.checkHealth();

        assertFalse(ownerInstance().isHealthy(), "the owner must mark its own stale instance unhealthy");
        awaitChanges(before + 1);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertFalse(replicaInstance().isHealthy(),
                        "node2 must converge to the owner's verdict, received as data"),
                "the transition never reached node2 — a replica that cannot see it is why "
                        + "the two nodes disagree about a live service");
        assertNotEquals(revisionWhileHealthy, session1.getRevision(),
                "health must participate in the revision: without it, anti-entropy can never "
                        + "repair a lost push for a client whose instances did not change");
    }

    @Test
    void ownerRecoveryIsPushedToo() {
        ageOwnerConnection(UNHEALTHY_MS + 5_000);
        health1.checkHealth();
        awaitChanges(2);
        assertFalse(ownerInstance().isHealthy(), "precondition: owner marked it unhealthy");
        assertFalse(replicaInstance().isHealthy(), "precondition: replica converged to unhealthy");

        int before = transport.changes.get();
        // Traffic resumes on the owner's connection: health is restored and must travel.
        cm1.refreshActiveTime("pub");
        health1.checkHealth();

        assertTrue(ownerInstance().isHealthy(), "precondition: the owner recovered its instance");
        awaitChanges(before + 1);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertTrue(replicaInstance().isHealthy(), "node2 must see the restored health"),
                "recovery never reached node2 — a replica left unhealthy steers traffic away "
                        + "from a live provider forever, which is worse than the outage itself");
    }
}
