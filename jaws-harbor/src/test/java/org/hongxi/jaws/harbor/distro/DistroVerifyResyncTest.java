package org.hongxi.jaws.harbor.distro;

import org.hongxi.jaws.harbor.ClientSession;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression lock for the #1 fix: a Distro verify mismatch must be repaired by the
 * OWNER re-pushing its authoritative copy to the reporting peer ({@link
 * DistroProtocol#resyncToPeer}) — the correct direction, mirroring Nacos
 * verify→syncToTarget — NOT by pulling from the (stale) peer. Lives in the
 * {@code distro} package so it can drive the package-private {@code resyncToPeer}
 * directly instead of waiting on the 5s verify scheduler.
 */
class DistroVerifyResyncTest {

    private static final String ADDR1 = "127.0.0.1:19848";
    private static final String ADDR2 = "127.0.0.1:19849";

    private final Loopback transport = new Loopback();
    private ConnectionManager cm1, cm2;
    private ServiceStorage st1, st2;
    private DistroProtocol d1, d2;

    /** Routes Distro messages between the two in-JVM DistroProtocols. */
    private static final class Loopback implements HarborNodeTransport {
        final Map<String, DistroProtocol> nodes = new ConcurrentHashMap<>();

        @Override
        public boolean syncData(String targetAddress, String resourceKey,
                                String operation, byte[] content) {
            DistroProtocol n = nodes.get(targetAddress);
            if (n == null) {
                return false;
            }
            n.onSync(resourceKey, operation, content);
            return true;
        }

        @Override
        public List<String> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos) {
            DistroProtocol n = nodes.get(targetAddress);
            return n == null ? List.of() : n.onVerify(verifyInfos);
        }

        @Override
        public byte[] getSnapshot(String targetAddress) {
            DistroProtocol n = nodes.get(targetAddress);
            return n == null ? null : n.onSnapshot();
        }

        @Override
        public void shutdown() { }
    }

    @BeforeEach
    void setUp() {
        nodes();
    }

    private void nodes() {
        cm1 = new ConnectionManager();
        st1 = new ServiceStorage(cm1, (a, b, c) -> { });
        d1 = new DistroProtocol(new ClusterManager(new URL("harbor", "127.0.0.1", 19848, "")),
                transport, st1, cm1);
        cm2 = new ConnectionManager();
        st2 = new ServiceStorage(cm2, (a, b, c) -> { });
        d2 = new DistroProtocol(new ClusterManager(new URL("harbor", "127.0.0.1", 19849, "")),
                transport, st2, cm2);
        transport.nodes.put(ADDR1, d1);
        transport.nodes.put(ADDR2, d2);
    }

    @Test
    void ownerResyncsItsAuthoritativeCopyToTheStalePeer() {
        // node1 owns client "A" with one instance.
        cm1.register("A", "10.0.0.1", "3.0.0", Map.of(), noop());
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "A");

        // node2 has NO copy of A at all — exactly what onVerify would report as a
        // mismatched client back to node1 (the owner).
        assertNull(cm2.getClientSession("A"), "precondition: peer is missing the client");

        // The owner repairs by re-pushing its current full state to that peer.
        d1.resyncToPeer(new ClusterMember(ADDR2), List.of("A"));

        // node2 converged to the owner's authoritative copy.
        assertEquals(1, st2.getInstances("public", "DEFAULT_GROUP", "svc").size(),
                "resync must deliver the owner's latest state to the stale peer");
        ClientSession copy = cm2.getClientSession("A");
        assertNotNull(copy);
        assertFalse(copy.isNativeClient(), "the received copy is a synced replica, not owned here");
        assertEquals(cm1.getClientSession("A").getRevision(), copy.getRevision(),
                "the replica's revision must match the owner after a resync");
    }

    @Test
    void resyncOnlyRepairsClientsThisNodeActuallyOwns() {
        // node1 owns A; node2 does not.
        cm1.register("A", "10.0.0.1", "3.0.0", Map.of(), noop());
        st1.registerInstance("public", "DEFAULT_GROUP", "svc", instance("10.0.0.1", 8081), "A");

        // node2 (which does NOT own A) must not fabricate/push a repair.
        d2.resyncToPeer(new ClusterMember(ADDR1), List.of("A"));

        assertNull(cm2.getClientSession("A"),
                "a node that doesn't own the client has nothing to re-push");
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
