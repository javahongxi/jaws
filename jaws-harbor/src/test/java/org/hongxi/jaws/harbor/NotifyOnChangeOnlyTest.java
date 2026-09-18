package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks that a subscriber announcement means a change in what subscribers can
 * route to — nothing else.
 * <p>
 * The regression this pins: a surviving service used to be announced even when the
 * caller changed nothing routable. Because the health sweep visits every service
 * every 5 s, that re-pushed each idle service in full to all of its subscribers on
 * every sweep — with a 5 s beat that is 17 280 redundant pushes a day per service,
 * growing with the number of services rather than the number of changes. Nacos
 * pushes on change, so {@code ServiceStorage.announceChange} is only called by paths
 * that add or remove an instance.
 * <p>
 * The flip side is what must STILL announce: a removal that leaves subscribers
 * behind (by API, by expiry, by connection closure, by a synced client going away) —
 * including the empty list the instant the last instance goes. Two things announce
 * NOTHING: a bare unsubscribe (it changes who receives pushes, not what the remaining
 * receivers route to), and the retirement of a fully empty service
 * ({@code ServiceStorage.retireIfEmpty}) — surviving subscribers were already told the
 * moment the last instance was removed, so once both index sets are empty there is no
 * one left to notify.
 */
class NotifyOnChangeOnlyTest {

    private static final String NS = "public";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String SVC = "svc";
    private static final ServiceKey KEY = ServiceKey.of(NS, GROUP, SVC);

    private ConnectionManager cm;
    private ServiceStorage storage;
    private final List<String> events = new ArrayList<>();

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

    /** A publisher connection with one live, healthy instance — the steady state. */
    private void givenLivePublisher() {
        cm.register("pub", "10.0.0.1", "3.0.0", Map.of(), noop());
        storage.registerInstance(NS, GROUP, SVC, instance("10.0.0.1", 8080), "pub");
    }

    /** One health-sweep pass, exactly what HealthCheckScheduler does every 5 s. */
    private void sweep() {
        storage.reconcileHealth(15_000);
        storage.cleanEmptyServices();
    }

    // ========================================================================
    // Idle must be silent.
    // ========================================================================

    @Test
    void registrationAnnouncesExactlyOnce() {
        givenLivePublisher();

        assertEquals(1, events.size(), "registering an instance is a change: " + events);
        assertEquals(KEY.toKeyString(), events.get(0));
    }

    @Test
    void idleSweepsAnnounceNothing() {
        givenLivePublisher();
        events.clear();

        for (int i = 0; i < 3; i++) {
            sweep();
        }

        assertTrue(events.isEmpty(),
                "a sweep that changed no data must not re-push every service to every "
                        + "subscriber — that is the churn this test pins: " + events);
    }

    @Test
    void anUnhealthyInstanceIsAnnouncedOnceAndNotAgainOnLaterSweeps() {
        givenLivePublisher();
        events.clear(); // the registration announced once already; measure only what follows
        cm.allConnections().stream()
                .filter(r -> r.connectionId().equals("pub"))
                .findFirst().orElseThrow().lastActiveTime().addAndGet(-20_000);

        sweep();
        assertEquals(1, events.size(), "the transition to unhealthy is a change: " + events);
        assertFalse(storage.getInstances(NS, GROUP, SVC).get(0).isHealthy());

        events.clear();
        sweep();
        sweep();
        assertTrue(events.isEmpty(),
                "re-announcing an unchanged verdict is the same churn: " + events);
    }

    @Test
    void unsubscribingAnnouncesNothingToThoseWhoStay() {
        givenLivePublisher();
        cm.register("sub", "10.0.0.2", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "sub");
        assertTrue(storage.getSubscriberConnections(KEY).contains("sub"), "precondition");
        events.clear();

        storage.removeSubscriber(NS, GROUP, SVC, "sub");

        assertTrue(events.isEmpty(),
                "one subscriber leaving changes who receives pushes, not what the others "
                        + "route to (the instance list is identical): " + events);
    }

    // ========================================================================
    // Real removals must still announce — once.
    // ========================================================================

    @Test
    void explicitDeregistrationAnnouncesToSurvivingSubscriber() {
        givenLivePublisher();
        cm.register("sub", "10.0.0.2", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "sub");
        events.clear();

        storage.deregisterInstance(NS, GROUP, SVC, instance("10.0.0.1", 8080), "pub");

        assertEquals(1, events.size(),
                "the last instance is gone but a subscriber stays: it must hear the empty "
                        + "list exactly once: " + events);
    }

    @Test
    void connectionClosureStillAnnouncesEachLostInstanceOnce() {
        givenLivePublisher();
        cm.register("pub2", "10.0.0.3", "3.0.0", Map.of(), noop());
        storage.registerInstance(NS, GROUP, SVC, instance("10.0.0.3", 8081), "pub2");
        events.clear();

        int removed = storage.deregisterInstancesByConnectionId("pub");

        assertEquals(1, removed, "precondition: one instance belonged to that connection");
        assertEquals(1, events.size(),
                "the connection lost an instance: subscribers must hear it, exactly once: " + events);
    }

    @Test
    void syncedClientGoingAwayStillAnnounces() {
        // Arrives as a synced replica (no cm.register, which would make it native
        // and applyClientSyncData would rightly refuse to overwrite the owner's copy).
        Instance inst = instance("10.0.0.9", 9090);
        storage.applyClientSyncData(new ClientSyncData("remote",
                List.of(KEY.toKeyString()), List.of(inst), 1L));
        cm.register("sub", "10.0.0.2", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "sub");
        assertEquals(1, storage.getInstances(NS, GROUP, SVC).size(), "precondition");
        events.clear();

        storage.removeSyncedClient("remote");

        assertEquals(1, events.size(),
                "the replica shed its last instance while a local subscriber stays: it must "
                        + "hear it exactly once: " + events);
    }

    @Test
    void retirementAfterLastUnsubscribeIsSilent() {
        givenLivePublisher();
        cm.register("sub", "10.0.0.2", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "sub");
        events.clear();

        // Losing the last instance is a real change for the surviving subscriber: it is
        // told the service is now empty, here, exactly once.
        storage.deregisterInstance(NS, GROUP, SVC, instance("10.0.0.1", 8080), "pub");
        assertEquals(1, events.size(),
                "the publisher is gone; the staying subscriber is told once: " + events);

        events.clear();
        // The last subscriber now leaves: the service is fully empty and is retired, but
        // there is no one left to notify — retirement announces nothing.
        storage.removeSubscriber(NS, GROUP, SVC, "sub");

        assertTrue(events.isEmpty(),
                "a service retired with its last subscriber gone must not announce to "
                        + "nobody: " + events);
        assertTrue(storage.getInstances(NS, GROUP, SVC).isEmpty(), "service retired: instances gone");
        assertTrue(storage.getSubscriberConnections(KEY).isEmpty(), "service retired: index dropped");
    }
}
