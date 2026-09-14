package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.Instance;
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
 * {@code ServiceStorage.checkAndCleanEmptyService} used to announce in its
 * {@code else} branch too, i.e. "service still exists, notify anyway". Since the
 * health sweep calls it for every service every 5 s, each idle service was
 * re-pushed in full to all of its subscribers every sweep: with the beat interval
 * at 5 s that is 17 280 redundant pushes a day per service, and it grows with the
 * number of services, not with the number of changes. Nacos pushes on change.
 * <p>
 * The other half of the rule is what must STILL announce: removing an instance
 * (by API, by expiry, by connection closure, by a synced client going away) and
 * the final update when a service is emptied. An unsubscribe changes who receives
 * pushes, not what the remaining receivers route to, so it announces nothing.
 */
class NotifyOnChangeOnlyTest {

    private static final String NS = "public";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String SVC = "svc";
    private static final String KEY = "public@@DEFAULT_GROUP@@svc";

    private ConnectionManager cm;
    private ServiceStorage storage;
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cm = new ConnectionManager();
        storage = new ServiceStorage(cm, (ns, g, svc) -> events.add(ns + "@@" + g + "@@" + svc));
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

    /** One health-sweep pass, exactly what HealthCheckManager does every 5 s. */
    private void sweep() {
        storage.markUnhealthyStale(15_000);
        storage.cleanEmptyServices();
    }

    // ========================================================================
    // Idle must be silent.
    // ========================================================================

    @Test
    void registrationAnnouncesExactlyOnce() {
        givenLivePublisher();

        assertEquals(1, events.size(), "registering an instance is a change: " + events);
        assertEquals(KEY, events.get(0));
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
        storage.getInstances(NS, GROUP, SVC).get(0).setLastBeat(System.currentTimeMillis() - 20_000);

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
    void explicitDeregistrationStillAnnounces() {
        givenLivePublisher();
        events.clear();

        storage.deregisterInstance(NS, GROUP, SVC, instance("10.0.0.1", 8080), "pub");

        assertEquals(1, events.size(),
                "removing a routable instance is a change, but must not be announced twice: "
                        + events);
    }

    @Test
    void expiredInstanceStillAnnounces() {
        givenLivePublisher();
        events.clear();

        storage.removeInstanceByIpPort(KEY, "10.0.0.1", 8080);

        assertEquals(1, events.size(), "expiry removes a routable instance: " + events);
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
                List.of(KEY), List.of(inst), 1L));
        assertEquals(1, storage.getInstances(NS, GROUP, SVC).size(), "precondition");
        events.clear();

        storage.removeSyncedClient("remote");

        assertEquals(1, events.size(),
                "a replica shedding a synced client is a change for local subscribers: " + events);
    }

    @Test
    void emptyingAServiceAnnouncesExactlyOneFinalUpdate() {
        givenLivePublisher();
        cm.register("sub", "10.0.0.2", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "sub");
        events.clear();

        storage.deregisterInstance(NS, GROUP, SVC, instance("10.0.0.1", 8080), "pub");
        assertEquals(1, events.size(), "the publisher is gone; subscribers stay, one update: " + events);

        events.clear();
        storage.removeSubscriber(NS, GROUP, SVC, "sub");

        assertEquals(1, events.size(),
                "the service is now empty and is being removed — subscribers get one final "
                        + "empty update, not two: " + events);
        assertTrue(storage.getInstances(NS, GROUP, SVC).isEmpty(), "precondition: service gone");
    }
}
