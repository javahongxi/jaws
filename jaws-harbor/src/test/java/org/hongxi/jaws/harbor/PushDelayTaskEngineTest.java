package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PushDelayTaskEngine} — the service-level, coalescing,
 * re-read-at-fire-time push engine. These lock the two semantics that make it
 * match Nacos push v2 rather than the old "cache + resend" retry:
 * <ol>
 *   <li>coalescing — many changes to one service collapse into a single push;</li>
 *   <li>reconcile — the push carries the state as of fire time, not the state
 *       captured when the change was registered (so no client can regress).</li>
 * </ol>
 */
class PushDelayTaskEngineTest {

    private static final ServiceKey SVC = ServiceKey.of("public", "DEFAULT_GROUP", "svc");

    /** A subscriber connection whose pushed payloads we can inspect. */
    private static final class Recording {
        final List<Message> received = new CopyOnWriteArrayList<>();

        StreamSubject<Message> subject() {
            return new StreamSubject<>() {
                @Override public void onNext(Message item) { received.add(item); }
                @Override public void onError(Throwable throwable) { }
                @Override public void onCompleted() { }
            };
        }

        int hostCountOfLastPush() {
            Payload p = (Payload) received.get(received.size() - 1);
            NotifySubscriberRequest req = JSON.parseObject(
                    new String(p.getBody().getValue().toByteArray(), StandardCharsets.UTF_8),
                    NotifySubscriberRequest.class);
            return req.getServiceInfo().getHosts().size();
        }
    }

    /**
     * Inbound frames keep arriving while the server is closing, so a push can be
     * requested after the engine shut down. It must be dropped quietly: letting
     * RejectedExecutionException escape puts it on a Netty worker thread, where the
     * transport can only log it as an unexpected channel error (measured twice per
     * integration-test teardown before this was fixed).
     */
    @Test
    void requestsAfterShutdownAreDroppedQuietly() {
        ConnectionManager cm = new ConnectionManager();
        ServiceStorage storage = new ServiceStorage(cm, key -> { });
        Recording sub = new Recording();
        cm.register("sub-1", "10.0.0.1", "3.0.0", Map.of(), sub.subject());
        storage.addSubscriber("public", "DEFAULT_GROUP", "svc", "sub-1");
        PushDelayTaskEngine engine = new PushDelayTaskEngine(storage, cm);

        engine.shutdown();

        assertDoesNotThrow(() -> engine.requestPush(SVC),
                "a closed engine has nobody left to deliver to - dropping is correct, "
                        + "throwing into the caller's channel is not");
        assertTrue(sub.received.isEmpty(), "and nothing may be pushed after shutdown");
    }

    @Test
    void coalescesMultipleChangesIntoOnePushCarryingLatestState() throws Exception {
        ConnectionManager cm = new ConnectionManager();
        ServiceStorage storage = new ServiceStorage(cm, key -> { });

        // A publisher connection that owns the instances, and a subscriber whose
        // push subject we record.
        cm.register("pub-1", "10.0.0.9", "3.0.0", Map.of(), noopSubject());
        Recording sub = new Recording();
        cm.register("sub-1", "10.0.0.1", "3.0.0", Map.of(), sub.subject());
        storage.addSubscriber("public", "DEFAULT_GROUP", "svc", "sub-1");

        PushDelayTaskEngine engine = new PushDelayTaskEngine(storage, cm);

        // Change #1, then register it and ask to push.
        storage.registerInstance("public", "DEFAULT_GROUP", "svc",
                instance("10.0.0.9", 8081, "iA"), "pub-1");
        engine.requestPush(SVC);

        // Change #2 within the coalescing window, then ask to push again.
        storage.registerInstance("public", "DEFAULT_GROUP", "svc",
                instance("10.0.0.9", 8082, "iB"), "pub-1");
        engine.requestPush(SVC);

        // Wait for the single coalesced push to land (bounded; not tied to the window).
        awaitTrue(() -> !sub.received.isEmpty(), 5_000);
        // Settle so any un-coalesced second push behind the first would surface here too.
        Thread.sleep(250);

        // Coalescing: two requestPush calls collapsed into exactly ONE push.
        assertEquals(1, sub.received.size(),
                "bursts on one service must coalesce into a single push");
        // Reconcile: that one push carries the CURRENT state (both instances),
        // proving the engine re-read at fire time rather than caching request #1.
        assertEquals(2, sub.hostCountOfLastPush(),
                "the coalesced push must carry the latest state, not the first snapshot");

        engine.shutdown();
    }

    @Test
    void readsLatestStateAtFireTimeEvenIfRegisteredAfterRequest() throws Exception {
        ConnectionManager cm = new ConnectionManager();
        ServiceStorage storage = new ServiceStorage(cm, key -> { });
        cm.register("pub-1", "10.0.0.9", "3.0.0", Map.of(), noopSubject());
        Recording sub = new Recording();
        cm.register("sub-1", "10.0.0.1", "3.0.0", Map.of(), sub.subject());
        storage.addSubscriber("public", "DEFAULT_GROUP", "svc", "sub-1");

        PushDelayTaskEngine engine = new PushDelayTaskEngine(storage, cm);

        // Ask to push while the service is still EMPTY …
        engine.requestPush(SVC);
        // … then register an instance during the delay window (before it fires).
        storage.registerInstance("public", "DEFAULT_GROUP", "svc",
                instance("10.0.0.9", 8081, "iA"), "pub-1");

        awaitTrue(() -> !sub.received.isEmpty(), 5_000);
        Thread.sleep(250);

        assertEquals(1, sub.received.size());
        // If the engine had cached the (empty) snapshot at requestPush time it
        // would push 0 hosts; because it re-reads at fire time, it pushes 1.
        assertEquals(1, sub.hostCountOfLastPush(),
                "engine must push the state as of fire time, not of requestPush time");

        engine.shutdown();
    }

    /**
     * Poll {@code condition} until true or {@code timeoutMs} elapses. Lets a push test
     * key on behaviour (a push landed, carrying the right state) instead of a hard-coded
     * sleep tied to {@code MERGE_DELAY_MS}, so moving that window does not silently
     * re-break — or needlessly slow down — the assertion.
     */
    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("condition not satisfied within " + timeoutMs + "ms");
    }

    private static Instance instance(String ip, int port, String id) {
        Instance inst = new Instance();
        inst.setIp(ip);
        inst.setPort(port);
        inst.setInstanceId(id);
        return inst;
    }

    private static StreamSubject<Message> noopSubject() {
        return new StreamSubject<>() {
            @Override public void onNext(Message item) { }
            @Override public void onError(Throwable throwable) { }
            @Override public void onCompleted() { }
        };
    }
}
