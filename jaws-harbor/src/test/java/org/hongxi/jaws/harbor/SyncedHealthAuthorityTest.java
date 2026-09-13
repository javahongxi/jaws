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
 * A Distro replica receives a snapshot whose {@code lastBeat} is frozen at push
 * time, because beats arrive on the owner's connection and are not forwarded per
 * beat. Judging that frozen beat against a local clock invents an outage the owner
 * never saw. Measured live: with a provider beating every 5 s, a non-owner node
 * marked its replica unhealthy 15 s after the last data sync — so every node that
 * does not own a connection shows a healthy provider as dead.
 * <p>
 * The two health tiers therefore split by authority, not by threshold:
 * <ul>
 *   <li>the {@code unhealthy} tier is judged by the owner ALONE and reaches a
 *       replica as data — which means a transition must bump the session revision
 *       (so anti-entropy can repair a lost push) and trigger a coalesced sync (so
 *       subscribers converge without waiting for a verify cycle);</li>
 *   <li>the {@code expired} tier is still evaluated on a replica, because it is
 *       the only reaper left when the OWNER node itself dies — nobody is left to
 *       push anything. That is sound only while the owner keeps re-publishing its
 *       clients ({@link DistroProtocol#refreshOwnedClients()}), which is what
 *       stops a long-lived client with no data changes from rotting on replicas.</li>
 * </ul>
 */
class SyncedHealthAuthorityTest {

    private static final String ADDR1 = "127.0.0.1:19848";
    private static final String ADDR2 = "127.0.0.1:19849";
    private static final long UNHEALTHY_MS = 15_000;
    private static final long EXPIRE_MS = 180_000;

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
        st1 = new ServiceStorage((ns, g, svc) -> { }, cm1, clientId -> d1.requestSyncChange(clientId));
        cm2 = new ConnectionManager();
        st2 = new ServiceStorage((ns, g, svc) -> notified2.add(ns + "@@" + g + "@@" + svc), cm2);

        ClusterManager c1 = new ClusterManager(new URL("harbor", "127.0.0.1", 19848, ""));
        c1.addMember(new ClusterMember(ADDR2));
        ClusterManager c2 = new ClusterManager(new URL("harbor", "127.0.0.1", 19849, ""));
        c2.addMember(new ClusterMember(ADDR1));

        d1 = new DistroProtocol(c1, transport, st1, cm1);
        d2 = new DistroProtocol(c2, transport, st2, cm2);
        transport.nodes.put(ADDR1, d1);
        transport.nodes.put(ADDR2, d2);

        health1 = new HealthCheckManager(cm1, st1, new ConnectionLifecycle(cm1, st1, d1));
        health2 = new HealthCheckManager(cm2, st2, new ConnectionLifecycle(cm2, st2, d2));

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

    /**
     * Put node2's replica in the state time alone produces: the owner pushed this
     * state a while ago and has been quietly beating since (no data change, hence
     * no new push). The owner's own copy is left untouched and healthy.
     */
    private void ageOnlyTheReplica(long millis) {
        long ownerBeat = System.currentTimeMillis();
        ownerInstance().setLastBeat(ownerBeat);
        ownerInstance().setHealthy(true);
        // Push a state whose beat is already old, then let the owner's clock move on
        // — what a replica of a live client looks like once its last push ages.
        ownerInstance().setLastBeat(ownerBeat - millis);
        pushOwnerStateToPeer();
        ownerInstance().setLastBeat(ownerBeat);
        notified2.clear(); // the fixture's own push re-notified; measure only what comes next
        assertTrue(ownerInstance().isHealthy(), "precondition: the owner sees itself healthy");
    }

    // ========================================================================
    // The unhealthy tier: judged by the owner alone.
    // ========================================================================

    @Test
    void replicaMustNotInventAnOutageTheOwnerNeverSaw() {
        ageOnlyTheReplica(UNHEALTHY_MS + 5_000);
        assertTrue(replicaInstance().isHealthy(), "precondition: the replica arrives healthy");

        health2.checkHealth();

        assertTrue(replicaInstance().isHealthy(),
                "a node that never received this client's beats must not judge health from a "
                        + "frozen copy — the owner is alive and beating every 5 s");
        // Deliberately NOT asserting on notified2: the sweep notifies for every
        // service regardless of change (ServiceStorage.checkAndCleanEmptyService's
        // else branch), so a notification proves nothing here. That push churn is
        // a separate defect from who may judge health, and is filed as such.
    }

    // ========================================================================
    // Health transitions are data: revision + coalesced push, both directions.
    // ========================================================================

    @Test
    void ownerVerdictIsPushedAndCarriesTheRevision() {
        ClientSession session1 = cm1.getClientSession("pub");
        long revisionWhileHealthy = session1.getRevision();
        int before = transport.changes.get();

        // The owner's OWN beat really did stop — this is the node allowed to judge.
        ownerInstance().setLastBeat(System.currentTimeMillis() - UNHEALTHY_MS - 5_000);
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
        ownerInstance().setLastBeat(System.currentTimeMillis() - UNHEALTHY_MS - 5_000);
        health1.checkHealth();
        awaitChanges(2);
        assertFalse(ownerInstance().isHealthy(), "precondition: owner marked it unhealthy");
        assertFalse(replicaInstance().isHealthy(), "precondition: replica converged to unhealthy");

        int before = transport.changes.get();
        // A beat arrives on the owner's connection: health is restored and must travel.
        st1.updateHeartbeatByConnectionId("pub");

        assertTrue(ownerInstance().isHealthy(), "precondition: the owner recovered its instance");
        awaitChanges(before + 1);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertTrue(replicaInstance().isHealthy(), "node2 must see the restored health"),
                "recovery never reached node2 — a replica left unhealthy steers traffic away "
                        + "from a live provider forever, which is worse than the outage itself");
    }

    // ========================================================================
    // The expired tier: still evaluated on a replica, and only sound because the
    // owner keeps re-publishing.
    // ========================================================================

    @Test
    void ownerRepublishesOwnedClientsSoReplicasDoNotRot() {
        long fresh = System.currentTimeMillis();
        ownerInstance().setLastBeat(fresh);
        int before = transport.changes.get();

        d1.refreshOwnedClients();
        awaitChanges(before + 1);

        assertEquals(fresh, replicaInstance().getLastBeat(),
                "the refresh pass must re-publish owned clients even with no logical change, "
                        + "otherwise a long-lived client's replica ages toward expiry on every "
                        + "node that does not own its connection");
    }

    @Test
    void replicaReapsAClientWhoseOwnerWentSilent() {
        // Nobody left to push: the owner died. Local expiry is the last resort and
        // must still fire on the replica, coarse as it is.
        ageOnlyTheReplica(EXPIRE_MS + 10_000);

        health2.checkHealth();

        assertTrue(st2.getInstances("public", "DEFAULT_GROUP", "svc").isEmpty(),
                "a replica whose owner stopped refreshing entirely must eventually be reaped "
                        + "locally — otherwise a dead client lingers in the cluster view forever");
    }
}
