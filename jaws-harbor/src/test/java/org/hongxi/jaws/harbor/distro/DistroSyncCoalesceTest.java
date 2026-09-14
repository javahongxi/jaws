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
        st1 = new ServiceStorage(cm1, (a, b, c) -> { });
        ClusterManager cluster1 = new ClusterManager(url(19848));
        cluster1.addMember(new ClusterMember(ADDR2));
        d1 = new DistroProtocol(cluster1, transport, st1, cm1);
        cm2 = new ConnectionManager();
        st2 = new ServiceStorage(cm2, (a, b, c) -> { });
        ClusterManager cluster2 = new ClusterManager(url(19849));
        cluster2.addMember(new ClusterMember(ADDR1));
        d2 = new DistroProtocol(cluster2, transport, st2, cm2);
        transport.nodes.put(ADDR1, d1);
        transport.nodes.put(ADDR2, d2);
        d1.start();
        d2.start();
        // node1 owns the client "C" with one instance.
        cm1.register("C", "10.0.0.1", "3.0.0", Map.of(), noop());
    }

    @Test
    void coalescesBurstIntoOneLatestPush() throws Exception {
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8082), "C");
        d1.requestSyncChange("C");

        Thread.sleep(400);

        assertEquals(1, transport.changes.get(),
                "two bursts for one client must coalesce into a single CHANGE push");
        assertEquals(2, st2.getInstances("public", "DEFAULT_GROUP", "svc").size(),
                "the one push must carry the current full state, not just the first change");
    }

    @Test
    void deleteCancelsPendingChange() throws Exception {
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "C");
        d1.requestSyncChange("C");          // queued CHANGE …
        d1.requestSyncDelete("C");          // … must be cancelled by the immediate DELETE

        Thread.sleep(400);

        assertEquals(0, transport.changes.get(),
                "a DELETE within the window must cancel the pending coalesced CHANGE");
        assertEquals(1, transport.deletes.get(), "DELETE is propagated immediately");
        assertNull(cm2.getClientSession("C"), "the peer must not be revived by the cancelled CHANGE");
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
