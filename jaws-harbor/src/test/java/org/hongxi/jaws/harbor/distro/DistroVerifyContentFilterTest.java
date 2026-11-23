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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the verify payload to exactly what Distro replicates: a native session
 * with no instances (config-only, subscribe-only, or fully expired) is never
 * synced — {@code doSyncChange} emits a DELETE for it and {@code onSnapshot}
 * skips it — so the peer cannot hold a copy. Presenting it for comparison
 * therefore reports a PERMANENT mismatch that {@code resyncToPeer}'s hasContent
 * guard can never repair: a WARN every verify tick, forever. Such sessions must
 * stay out of the comparison entirely. Lives in the {@code distro} package so it
 * can drive the package-private {@code runVerifyTask} directly instead of
 * waiting on the 5s verify scheduler.
 */
class DistroVerifyContentFilterTest {

    private static final String ADDR1 = "127.0.0.1:19848";
    private static final String ADDR2 = "127.0.0.1:19849";

    private final Recording transport = new Recording();
    private ConnectionManager cm1, cm2;
    private ServiceStorage st1, st2;
    private DistroProtocol d1, d2;

    /** Routes Distro messages in-JVM and records every verify payload per target. */
    private static final class Recording implements HarborNodeTransport {
        final Map<String, DistroProtocol> nodes = new ConcurrentHashMap<>();
        final Map<String, List<ClientVerifyInfo>> verifies = new ConcurrentHashMap<>();
        final AtomicInteger syncs = new AtomicInteger();

        @Override
        public CompletableFuture<Boolean> syncData(String targetAddress, String resourceKey,
                                String operation, byte[] content) {
            syncs.incrementAndGet();
            DistroProtocol n = nodes.get(targetAddress);
            if (n == null) {
                return CompletableFuture.completedFuture(false);
            }
            n.onSync(resourceKey, operation, content);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<List<String>> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos) {
            verifies.put(targetAddress, List.copyOf(verifyInfos));
            DistroProtocol n = nodes.get(targetAddress);
            return CompletableFuture.completedFuture(n == null ? List.of() : n.onVerify(verifyInfos));
        }

        @Override
        public CompletableFuture<byte[]> getSnapshot(String targetAddress) {
            DistroProtocol n = nodes.get(targetAddress);
            return CompletableFuture.completedFuture(n == null ? null : n.onSnapshot());
        }

        @Override
        public void shutdown() { }
    }

    @BeforeEach
    void setUp() {
        cm1 = new ConnectionManager();
        st1 = new ServiceStorage(cm1, key -> { });
        ClusterManager cluster1 = new ClusterManager(new URL("harbor", "127.0.0.1", 19848, ""));
        cluster1.addMember(new ClusterMember(ADDR2));
        d1 = new DistroProtocol(cluster1, transport, st1, cm1);
        cm2 = new ConnectionManager();
        st2 = new ServiceStorage(cm2, key -> { });
        d2 = new DistroProtocol(new ClusterManager(new URL("harbor", "127.0.0.1", 19849, "")),
                transport, st2, cm2);
        transport.nodes.put(ADDR1, d1);
        transport.nodes.put(ADDR2, d2);
        // Neither protocol is started: each test drives runVerifyTask directly,
        // and the loopback transport settles synchronously, so no scheduler or
        // background repair path can confound the payload assertions.
    }

    @Test
    void contentlessNativeSessionIsExcludedFromVerify() {
        // A config/subscribe-only connection: native session, zero instances.
        cm1.register("cfg", "10.0.0.2", "3.0.0", Map.of(), noop());
        st1.addSubscriber("public", "DEFAULT_GROUP", "svc", "cfg");

        d1.runVerifyTask();

        assertNull(transport.verifies.get(ADDR2),
                "a node whose only native session holds no replicable content must not "
                        + "present it for comparison — the peer cannot hold a copy, so the "
                        + "mismatch would be permanent and unrepairable");
        assertEquals(0, transport.syncs.get(),
                "no resync traffic may be stirred up for a contentless session");
    }

    @Test
    void contentfulNativeSessionIsStillVerifiedAndRepaired() {
        cm1.register("A", "10.0.0.1", "3.0.0", Map.of(), noop());
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "A");

        d1.runVerifyTask();

        List<ClientVerifyInfo> sent = transport.verifies.get(ADDR2);
        assertNotNull(sent, "a session with replicable content must still be verified");
        assertEquals(1, sent.size());
        assertEquals("A", sent.get(0).getConnectionId());
        assertEquals(cm1.getClientSession("A").getRevision(), sent.get(0).getRevision());
        // The mismatch→resync repair loop stays intact for replicable clients.
        assertEquals(1, st2.getInstances("public", "DEFAULT_GROUP", "svc").size(),
                "the peer missing a contentful client must still be repaired by resync");
    }

    @Test
    void emptiedSessionDropsOutOfVerify() {
        cm1.register("A", "10.0.0.1", "3.0.0", Map.of(), noop());
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "A");
        d1.runVerifyTask();
        assertNotNull(transport.verifies.get(ADDR2));
        assertEquals("A", transport.verifies.get(ADDR2).get(0).getConnectionId(),
                "precondition: while publishing, the session is verified");

        // The last instance goes away (deregister/expiry) but the connection —
        // and its native session — stays alive.
        st1.deregisterInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "A");
        assertNotNull(cm1.getClientSession("A"), "precondition: the session itself survives");
        transport.verifies.clear();

        d1.runVerifyTask();

        assertNull(transport.verifies.get(ADDR2),
                "an emptied session holds nothing replicable — it must drop out of the "
                        + "comparison instead of reporting a mismatch no resync can repair");
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