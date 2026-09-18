package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the reclamation of a REPLICATED client session — the last leak left after
 * health authority was fixed.
 * <p>
 * When the node owning a client's connection dies, nothing can any longer announce
 * that client's departure: no beat arrives (the beats went to the dead node), no
 * Distro DELETE arrives (its owner is gone), and the connection watchdog cannot see
 * the replica at all because {@code putClientSession} never creates a
 * {@code ConnectionRecord} — liveness rides on that record's own clock. Live
 * evidence: after killing the owning node, the
 * replica's instances were eventually dropped by the beat-expiry tier while
 * {@code removed synced client} was logged zero times — the {@link ClientSession}
 * shell, and its reverse-index entries, stayed behind for the process lifetime.
 * <p>
 * The predicate is therefore "no peer has confirmed this replica for a full expiry
 * window", using {@code lastRenewTime}, which is refreshed only when a sync
 * is applied and when the owner's revision matches during verify. A replica that IS
 * confirmed is live by construction; one that is not means the owner stopped — the
 * client or its node. Native sessions must never be touched by this tier: they are
 * authoritative locally and judge their own clients through the beat tiers.
 * <p>
 * A subscription-only replica shell — a shape this tier used to have to reap — can no
 * longer exist at all: subscriptions are not replicated, so such a client never
 * produces a payload worth syncing (see {@code SubscriptionStaysLocalTest}).
 */
class SyncedSessionReclamationTest {

    private static final String NS = "public";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String SVC = "svc";
    private static final String KEY = "public@@DEFAULT_GROUP@@svc";
    private static final long WINDOW_MS = 180_000;

    private ConnectionManager cm;
    private ServiceStorage storage;
    private final List<String> events = new ArrayList<>();

    private static class NoopTransport implements HarborNodeTransport {
        @Override public boolean syncData(String a, String c, String op, byte[] b) { return false; }
        @Override public List<String> syncVerify(String a, List<ClientVerifyInfo> v) { return List.of(); }
        @Override public byte[] getSnapshot(String a) { return null; }
        @Override public void shutdown() { }
    }

    @BeforeEach
    void setUp() {
        cm = new ConnectionManager();
        storage = new ServiceStorage(cm, service -> events.add(service.toKeyString()));
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

    /** Install a replica exactly as an inbound Distro sync would. */
    private void givenReplicaOf(String connectionId, List<String> serviceKeys, List<Instance> instances) {
        storage.applyClientSyncData(new ClientSyncData(connectionId, serviceKeys, instances, 7L));
        assertNotNull(cm.getClientSession(connectionId), "precondition: replica session installed");
        assertFalse(cm.getClientSession(connectionId).isNativeClient(),
                "precondition: the replica must not look native here");
        // First-seen replica now announces its newly-routable services (replica-side
        // CHANGE notify). This class measures the reclamation path, so drop the setup
        // announce; the notify behaviour is asserted by the dedicated test below.
        events.clear();
    }

    private void ageReplica(String connectionId, long millis) {
        // The reaper measures silence FROM THE OWNER; local mutations must not count.
        cm.getClientSession(connectionId).setLastRenewTime(System.currentTimeMillis() - millis);
    }

    // ========================================================================

    @Test
    void replicaChangeNotifiesLocalSubscribersOnlyOnContentChange() {
        // Parity with the delete path: a replicated CHANGE must announce like a
        // replicated DELETE (removeSyncedClient -> onServiceChange), so subscribers on
        // a non-owner node are pushed rather than waiting for their next poll. Gated on
        // content actually moving (client-level revision), so an idempotent re-push /
        // verify re-sync of an unchanged client stays silent (NotifyOnChangeOnly).
        // Called directly (not via givenReplicaOf, which clears events as setup noise).
        storage.applyClientSyncData(new ClientSyncData(
                "snap", List.of(KEY), List.of(instance("10.0.0.9", 9090)), 7L));
        assertEquals(1, events.size(),
                "a first-seen replica announces its newly-routable service once: " + events);

        events.clear();
        // Same connectionId, same revision => owner re-pushed identical state => no announce.
        storage.applyClientSyncData(new ClientSyncData(
                "snap", List.of(KEY), List.of(instance("10.0.0.9", 9090)), 7L));
        assertTrue(events.isEmpty(),
                "an idempotent re-sync at the same revision must not re-announce: " + events);

        events.clear();
        // Revision moves (owner added an instance) => announce the service once.
        storage.applyClientSyncData(new ClientSyncData(
                "snap", List.of(KEY, KEY),
                List.of(instance("10.0.0.9", 9090), instance("10.0.0.9", 9091)), 12L));
        assertEquals(1, events.size(),
                "a content change (new revision) announces the service once: " + events);
    }

    @Test
    void replicaUnconfirmedByItsOwnerIsReaped() {
        givenReplicaOf("remote", List.of(KEY), List.of(instance("10.0.0.9", 9090)));
        cm.register("sub", "10.0.0.2", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "sub");
        assertEquals(1, storage.getInstances(NS, GROUP, SVC).size(), "precondition: instance visible");
        ageReplica("remote", WINDOW_MS + 1_000);

        int reaped = storage.reapStaleSyncedClients(WINDOW_MS);

        assertEquals(1, reaped);
        assertNull(cm.getClientSession("remote"),
                "the session shell must go, not only its instances");
        assertTrue(storage.getInstances(NS, GROUP, SVC).isEmpty(),
                "and the replicated instance must disappear from this node's view");
        assertEquals(1, events.size(),
                "dropping the replica's last instance while a subscriber stays announces "
                        + "exactly once: " + events);
    }

    @Test
    void confirmedReplicaSurvives() {
        givenReplicaOf("remote", List.of(KEY), List.of(instance("10.0.0.9", 9090)));
        ageReplica("remote", WINDOW_MS - 30_000); // one refresh cycle ago

        assertEquals(0, storage.reapStaleSyncedClients(WINDOW_MS));
        assertNotNull(cm.getClientSession("remote"));
        assertEquals(1, storage.getInstances(NS, GROUP, SVC).size(),
                "an owner that still confirms its client must not lose it on replicas");
        assertTrue(events.isEmpty(), "a surviving replica announces nothing: " + events);
    }

    @Test
    void ownerVerifyAloneSustainsAnUnchangedReplicaAcrossWindows() {
        // Regression pin for the CLIENT_REFRESH removal. Nothing re-pushes an
        // unchanged client any more; the only periodic signal its replicas receive is
        // the owner's 5s verify. That verify must, on its own, keep a replica alive —
        // renewal rides on verify, exactly as Nacos's verifyClient calls
        // setLastRenewTime on a revision match (ConnectionBasedClientManager:147).
        // If onVerify ever stops stamping the clock, this goes red: having crossed the
        // deadline, the replica would be reclaimed by reapStaleSyncedClients.
        DistroProtocol distro = new DistroProtocol(
                new ClusterManager(new URL("harbor", "127.0.0.1", 19848, "")),
                new NoopTransport(), storage, cm);
        givenReplicaOf("remote", List.of(KEY), List.of(instance("10.0.0.9", 9090)));

        for (int window = 1; window <= 3; window++) {
            // Push the replica past its deadline: on its own it now looks orphaned.
            ageReplica("remote", WINDOW_MS + 1_000);
            long beforeRenew = cm.getClientSession("remote").getLastRenewTime();

            // Owner still holds "remote" at revision 7 (no logical change) and verifies it.
            List<String> mismatched = distro.onVerify(List.of(new ClientVerifyInfo("remote", 7L)));

            assertTrue(mismatched.isEmpty(),
                    "window " + window + ": an unchanged replica must verify clean, got " + mismatched);
            assertTrue(cm.getClientSession("remote").getLastRenewTime() > beforeRenew,
                    "window " + window + ": a verify revision-match must stamp lastRenewTime — with the "
                            + "periodic re-push gone this is the only thing keeping a no-change replica alive");
            assertEquals(0, storage.reapStaleSyncedClients(WINDOW_MS),
                    "window " + window + ": verify renewal alone must spare the replica from reclamation");
            assertNotNull(cm.getClientSession("remote"),
                    "the session must survive purely on verify, across many reclaim windows");
        }
        assertTrue(events.isEmpty(), "a surviving replica announces nothing: " + events);
    }

    @Test
    void thisTierNeverTouchesNativeSessions() {
        // A node's own client: authoritative locally, judged by the beat tiers.
        // Nothing here may evict it — an idle native session is not a dead one.
        cm.register("mine", "10.0.0.1", "3.0.0", Map.of(), noop());
        storage.registerInstance(NS, GROUP, SVC, instance("10.0.0.1", 8080), "mine");
        cm.getClientSession("mine").setLastRenewTime(1L); // the confirmation clock looks dead
        events.clear(); // registerInstance announces once, legitimately

        assertEquals(0, storage.reapStaleSyncedClients(WINDOW_MS));
        assertNotNull(cm.getClientSession("mine"), "a native session must never be reaped here");
        assertEquals(1, storage.getInstances(NS, GROUP, SVC).size());
        assertTrue(events.isEmpty(), "and nothing should be announced: " + events);
    }

    @Test
    void localMutationDoesNotBuyAReplicaAnotherWindow() {
        // A replica must be reaped one window after the OWNER went silent, not after
        // the last thing this node did to the copy. Removing one of the replica's
        // instances is a real local mutation that changes what is routed here — but it
        // must not refresh the confirmation clock and buy the replica another window.
        givenReplicaOf("remote", List.of(KEY), List.of(instance("10.0.0.9", 9090)));
        ageReplica("remote", WINDOW_MS + 1_000);
        storage.deregisterInstance(NS, GROUP, SVC, instance("10.0.0.9", 9090), "remote");
        assertTrue(storage.getInstances(NS, GROUP, SVC).isEmpty(),
                "precondition: the local removal actually shed the instance (not a no-op)");

        assertEquals(1, storage.reapStaleSyncedClients(WINDOW_MS),
                "a local removal must not extend the tolerance the owner's silence earned");
        assertNull(cm.getClientSession("remote"));
    }

    @Test
    void theWatchdogActuallyRunsTheReaper() {
        ClusterManager cluster = new ClusterManager(new URL("harbor", "127.0.0.1", 19848, ""));
        DistroProtocol distro = new DistroProtocol(cluster, new NoopTransport(), storage, cm);
        HealthCheckScheduler health = new HealthCheckScheduler(cm, storage,
                new ConnectionCleanup(cm, storage, distro));

        givenReplicaOf("remote", List.of(KEY), List.of(instance("10.0.0.9", 9090)));
        ageReplica("remote", WINDOW_MS + 1_000);
        events.clear();

        health.checkHealth();

        assertNull(cm.getClientSession("remote"),
                "the periodic sweep must reclaim the orphaned replica — otherwise the leak "
                        + "only closes when the process restarts");
    }
}
