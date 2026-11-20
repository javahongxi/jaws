package org.hongxi.jaws.harbor.distro;

import org.hongxi.jaws.harbor.ConnectionManager;
import org.hongxi.jaws.harbor.ServiceStorage;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import com.google.protobuf.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the sync-side coalescing (the outbound mirror of {@code PushDelayTaskEngine}):
 * bursts of changes for one client merge into a single push, and that push carries
 * the client's CURRENT full state re-read at fire time; a DELETE is propagated
 * immediately and cancels any coalesced CHANGE still queued.
 */
class DistroSyncCoalesceTest {

    private static final String ADDR1 = "127.0.0.1:19848";
    private static final String ADDR2 = "127.0.0.1:19849";

    private Recording transport;
    private ConnectionManager cm1, cm2;
    private ServiceStorage st1, st2;
    private DistroProtocol d1, d2;

    /** Counts CHANGE vs DELETE deliveries to a target and delegates to onSync. */
    private static final class Recording implements HarborNodeTransport {
        final Map<String, DistroProtocol> nodes = new ConcurrentHashMap<>();
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();

        @Override
        public boolean syncData(String addr, String cid, String op, byte[] content) {
            if (ADDR2.equals(addr)) {
                if (DistroProtocol.OP_DELETE.equals(op)) {
                    deletes.incrementAndGet();
                } else {
                    changes.incrementAndGet();
                }
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
        st1 = new ServiceStorage(cm1, key -> { });
        ClusterManager cluster1 = new ClusterManager(url(19848));
        cluster1.addMember(new ClusterMember(ADDR2));
        d1 = new DistroProtocol(cluster1, transport, st1, cm1);
        cm2 = new ConnectionManager();
        st2 = new ServiceStorage(cm2, key -> { });
        ClusterManager cluster2 = new ClusterManager(url(19849));
        cluster2.addMember(new ClusterMember(ADDR1));
        d2 = new DistroProtocol(cluster2, transport, st2, cm2);
        transport.nodes.put(ADDR1, d1);
        transport.nodes.put(ADDR2, d2);
        d1.start();
        // d2 is intentionally left un-started: this class only pins d1's outbound
        // coalescing, and d2 merely needs to apply inbound syncs (onSync runs
        // regardless of running). Starting d2 would arm its one-shot snapshot load
        // (~1s after start), which pulls C back from d1 and would confound
        // deleteCancelsPendingChange's "the peer was never revived" proof.
        // node1 owns the client "C" with one instance.
        cm1.register("C", "10.0.0.1", "3.0.0", Map.of(), noop());
    }

    @Test
    void coalescesBurstIntoOneLatestPush() throws Exception {
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8082), "C");
        d1.requestSyncChange("C");

        // Wait for the single coalesced CHANGE to land (bounded; not tied to the window).
        awaitTrue(() -> transport.changes.get() >= 1, 5_000);
        // Settle so an un-coalesced duplicate would surface and the peer's state fully applies.
        Thread.sleep(250);

        assertEquals(1, transport.changes.get(),
                "two bursts for one client must coalesce into a single CHANGE push");
        assertEquals(2, st2.getInstances("public", "DEFAULT_GROUP", "svc").size(),
                "the one push must carry the current full state, not just the first change");
    }

    @Test
    void deleteCancelsPendingChange() throws Exception {
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");          // coalesced CHANGE, would fire at +SYNC_MERGE (~1s) …
        d1.requestSyncDelete("C");          // … must be cancelled by the immediate DELETE

        awaitTrue(() -> transport.deletes.get() >= 1, 3_000);   // DELETE is propagated immediately

        // Proving a scheduled task did NOT fire cannot be a pure await — it needs to
        // outlast that task's own deadline, so wait past the 1s sync-merge window (an
        // un-cancelled CHANGE would have flushed by now). This stays under the 5s verify
        // tick so d1's periodic verify cannot resync C, and d2 is left un-started so its
        // snapshot load never revives C: the only remaining way C could reach the peer is
        // the very CHANGE we assert was cancelled.
        Thread.sleep(1_500);

        assertEquals(0, transport.changes.get(),
                "a DELETE within the window must cancel the pending coalesced CHANGE");
        assertEquals(1, transport.deletes.get(), "DELETE is propagated immediately");
        assertNull(cm2.getClientSession("C"), "the peer must not be revived by the cancelled CHANGE");
    }

    /**
     * Poll {@code condition} until true or {@code timeoutMs} elapses. Lets a positive
     * "a push landed" assertion key on behaviour instead of a sleep tied to
     * {@code DistroProtocol.SYNC_MERGE_DELAY_MS}.
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

    private static URL url(int port) {
        return new URL("harbor", "127.0.0.1", port, "");
    }

    private static Instance instance(String ip, int port) {
        Instance i = new Instance();
        i.setIp(ip);
        i.setPort(port);
        i.setInstanceId(ip + "#" + port + "#DEFAULT_GROUP@@svc");
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
