package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks harbor's connection-based ephemeral health model (Nacos 2.x): an instance
 * is healthy iff its owning connection is active. A connection idle past
 * {@code INSTANCE_UNHEALTHY_TIMEOUT} has its instances marked UNHEALTHY (kept,
 * subscribers told to steer away); activity restores them. Health is reconciled by
 * {@link ServiceStorage#reconcileHealth(long)} against the connection's liveness
 * clock — there is no per-instance beat. Tests drive the clock directly (no scheduler).
 */
class EphemeralHealthTierTest {

    private ConnectionManager cm;
    private ServiceStorage storage;
    private Instance inst;
    private final List<String> notified = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        cm = new ConnectionManager();
        storage = new ServiceStorage(cm, service -> notified.add(service.toKeyString()));
        cm.register("pub", "10.0.0.1", "3.0.0", Map.of(), noop());
        inst = instance("10.0.0.1", 8080, "i1");
        storage.registerInstance("public", "DEFAULT_GROUP", "svc", inst, "pub");
        notified.clear(); // discard the notify from the initial registration
    }

    private ConnectionManager.ConnectionRecord record() {
        return cm.allConnections().stream()
                .filter(r -> r.connectionId().equals("pub"))
                .findFirst().orElseThrow();
    }

    @Test
    void idleConnectionMarksUnhealthyAndNotifies() {
        record().lastActiveTime().addAndGet(-20_000);
        storage.reconcileHealth(15_000);
        assertFalse(inst.isHealthy(), "an instance whose connection idled past the threshold must be flagged unhealthy");
        assertTrue(notified.contains("public@@DEFAULT_GROUP@@svc"),
                "marking unhealthy must re-notify so subscribers stop routing to it");
    }

    @Test
    void activeConnectionIsNotFlagged() {
        storage.reconcileHealth(15_000);
        assertTrue(inst.isHealthy(), "an active connection keeps its instances healthy");
        assertTrue(notified.isEmpty(), "no spurious notify when nothing went unhealthy");
    }

    @Test
    void activityRestoresHealthAndNotifies() {
        record().lastActiveTime().addAndGet(-20_000);
        storage.reconcileHealth(15_000);
        assertFalse(inst.isHealthy());
        notified.clear();

        cm.touch("pub");            // the connection becomes active again
        storage.reconcileHealth(15_000);

        assertTrue(inst.isHealthy(), "connection activity must restore a previously-unhealthy instance");
        assertTrue(notified.contains("public@@DEFAULT_GROUP@@svc"),
                "restoring health must re-announce so subscribers route back");
    }

    private static Instance instance(String ip, int port, String id) {
        Instance i = new Instance();
        i.setIp(ip);
        i.setPort(port);
        i.setInstanceId(id);
        return i;
    }

    private static StreamSubject<Message> noop() {
        return new StreamSubject<>() {
            @Override public void onNext(Message item) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
    }
}
