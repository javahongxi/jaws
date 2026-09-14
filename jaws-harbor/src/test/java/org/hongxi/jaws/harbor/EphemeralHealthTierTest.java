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
 * Locks Nacos's two-tier ephemeral health model in harbor: a stale beat first marks
 * an instance UNHEALTHY (kept, subscribers told to steer away) at
 * {@code INSTANCE_UNHEALTHY_TIMEOUT}, and only later is it deleted at the expiry
 * window. A returning beat restores health. These test {@link ServiceStorage}'s
 * public health APIs directly (no scheduler involved).
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

    @Test
    void staleBeatMarksUnhealthyAndNotifies() {
        inst.setLastBeat(System.currentTimeMillis() - 20_000);
        storage.markUnhealthyStale(15_000);
        assertFalse(inst.isHealthy(), "an instance whose beat stopped beyond the threshold must be flagged unhealthy");
        assertTrue(notified.contains("public@@DEFAULT_GROUP@@svc"),
                "marking unhealthy must re-notify so subscribers stop routing to it");
    }

    @Test
    void freshBeatIsNotFlagged() {
        storage.markUnhealthyStale(15_000);
        assertTrue(inst.isHealthy(), "a fresh beat must remain healthy");
        assertTrue(notified.isEmpty(), "no spurious notify when nothing went unhealthy");
    }

    @Test
    void returningBeatRestoresHealthAndNotifies() {
        inst.setLastBeat(System.currentTimeMillis() - 20_000);
        storage.markUnhealthyStale(15_000);
        assertFalse(inst.isHealthy());
        notified.clear();

        storage.updateHeartbeatByConnectionId("pub");

        assertTrue(inst.isHealthy(), "a returning beat must restore a previously-unhealthy instance");
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
