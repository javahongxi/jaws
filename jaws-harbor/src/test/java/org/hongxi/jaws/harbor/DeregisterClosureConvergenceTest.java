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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks peer convergence when a client gracefully deregisters and its connection
 * closes INSIDE the coalescing window — the graceful-shutdown shape of every
 * well-behaved provider (unregister, then disconnect a few hundred ms later).
 * <p>
 * The race: the deregister queues a coalesced push, the closure transaction then
 * sees an EMPTY ownership snapshot (the instances are already gone) and evicts the
 * session; the queued push dies with it ({@code buildClientSyncData} returns null
 * once the session is evicted), so no DELETE ever leaves and the peer keeps the
 * last CHANGE forever — verify cannot repair it either, since the owner no longer
 * holds the client to vouch for it. The closure must therefore treat a queued
 * ticket as ownership too and emit the terminal DELETE itself.
 */
class DeregisterClosureConvergenceTest {

    private static final String ADDR1 = "127.0.0.1:19848";
    private static final String ADDR2 = "127.0.0.1:19849";

    private ConnectionManager cm1, cm2;
    private ServiceStorage st1, st2;
    private DistroProtocol d1, d2;
    private Recording transport;
    private ConnectionCleanup cleanup;

    /** Counts CHANGE vs DELETE deliveries to the peer and delegates to onSync. */
    private static final class Recording implements HarborNodeTransport {
        final Map<String, DistroProtocol> nodes = new ConcurrentHashMap<>();
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();

        @Override
        public CompletableFuture<Boolean> syncData(String addr, String cid, String op, byte[] content) {
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
                return CompletableFuture.completedFuture(true);
            }
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<List<String>> syncVerify(String addr, List<ClientVerifyInfo> v) {
            DistroProtocol n = nodes.get(addr);
            return CompletableFuture.completedFuture(n == null ? List.of() : n.onVerify(v));
        }

        @Override
        public CompletableFuture<byte[]> getSnapshot(String addr) {
            DistroProtocol n = nodes.get(addr);
            return CompletableFuture.completedFuture(n == null ? null : n.onSnapshot());
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
        // d2 is left un-started on purpose: its snapshot load (~1s after start)
        // and periodic verify would confound the convergence proof — the peer
        // must converge from the owner's DELETE alone, not from a background
        // repair path. onSync applies inbound syncs regardless of running.
        cleanup = new ConnectionCleanup(cm1, st1, d1, new ConfigListenContext());
        cm1.register("C", "10.0.0.1", "3.0.0", Map.of(), noop());
    }

    @Test
    void deregisterThenCloseWithinWindowConvergesPeer() throws Exception {
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");
        // Precondition: the peer holds the published instance (the would-be residue).
        awaitTrue(() -> st2.getInstances("public", "DEFAULT_GROUP", "svc").size() == 1, 5_000);

        // Graceful shutdown: deregister (queues a coalesced push, exactly as the
        // server's InstanceRequest handler does), then the connection closes well
        // inside the 1s window — no sleep needed, immediate is within the window.
        st1.deregisterInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");
        cleanup.cleanup("C");

        // The DELETE must ride the closure itself: bounded well under the window
        // so a repair that merely waits for the queued push (which dies with the
        // session) or for a verify cycle cannot pass.
        awaitTrue(() -> st2.getInstances("public", "DEFAULT_GROUP", "svc").isEmpty(), 1_000);
        assertNull(cm2.getClientSession("C"), "the peer's replica session must be gone, not just emptied");
        assertTrue(transport.deletes.get() >= 1, "convergence must come from a Distro DELETE");
    }

    @Test
    void deregisterSettledBeforeCloseAddsNoDuplicateDelete() throws Exception {
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");
        awaitTrue(() -> st2.getInstances("public", "DEFAULT_GROUP", "svc").size() == 1, 5_000);

        st1.deregisterInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");
        // Let the coalesced push fire and settle: it carries the emptied state as
        // a DELETE, so the peer has already converged before the connection closes.
        awaitTrue(() -> st2.getInstances("public", "DEFAULT_GROUP", "svc").isEmpty(), 5_000);
        int deletesBeforeClose = transport.deletes.get();

        cleanup.cleanup("C");
        Thread.sleep(250);

        assertEquals(deletesBeforeClose, transport.deletes.get(),
                "a settled deregister leaves no queued ticket — the closure must not re-send a DELETE");
        assertNull(cm2.getClientSession("C"));
    }

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