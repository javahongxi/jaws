package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks that a subscription is connection-LOCAL state and never enters the Distro
 * replication payload — the Nacos shape: {@code AbstractClient.generateSyncData()}
 * iterates publishers only, so a subscriber list never crosses a node boundary.
 * <p>
 * Two harms this excludes. A replicated subscriber id would land in this node's
 * push-target index ({@code subscriberIndexes}), where every push pass then looks
 * for a connection it cannot write to — noise that is worse, it makes the index lie
 * about what "connections currently subscribed" means on this node. And a
 * subscription-only client replicated as a replica shell had no DELETE path at all:
 * closure announces departure only when the client published something, so such a
 * shell could only be reclaimed by the orphan sweep a full expiry window later.
 *
 * @author shenhongxi
 */
class SubscriptionStaysLocalTest {

    private static final String NS = "public";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String SVC = "svc";
    private static final ServiceKey KEY = ServiceKey.of(NS, GROUP, SVC);

    private ConnectionManager cm;
    private ServiceStorage storage;

    private static class RecordingTransport implements HarborNodeTransport {
        record Send(String target, String clientId, String op, byte[] content) { }
        final List<Send> sends = new CopyOnWriteArrayList<>();

        @Override
        public boolean syncData(String targetAddress, String resourceKey,
                                String operation, byte[] content) {
            sends.add(new Send(targetAddress, resourceKey, operation, content));
            return true;
        }
        @Override public List<String> syncVerify(String a, List<ClientVerifyInfo> v) { return List.of(); }
        @Override public byte[] getSnapshot(String a) { return null; }
        @Override public void shutdown() { }
    }

    private RecordingTransport transport;
    private DistroProtocol protocol;

    @BeforeEach
    void setUp() {
        cm = new ConnectionManager();
        storage = new ServiceStorage(cm, key -> { });
        ClusterManager cluster = new ClusterManager(new URL("grpc", "10.0.0.1", 9848, ""));
        cluster.addMember(new ClusterMember("10.0.0.1:9848"));   // self
        cluster.addMember(new ClusterMember("10.0.0.2:9848"));   // one peer to replicate to
        transport = new RecordingTransport();
        protocol = new DistroProtocol(cluster, transport, storage, cm);
        protocol.start();   // doSyncChange only fires while the protocol is running
    }

    @AfterEach
    void tearDown() {
        protocol.shutdown();
    }

    // ========================================================================

    @Test
    void subscriptionOnlyClientExportsNothingReplicable() {
        cm.register("watcher", "10.0.0.9", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "watcher");

        ClientSyncData data = storage.buildClientSyncData("watcher");
        assertTrue(data.getServiceKeys() == null || data.getServiceKeys().isEmpty(),
                "a subscriber holds no routable instance, so the payload carries no service: "
                        + data.getServiceKeys());
        assertTrue(data.getInstances() == null || data.getInstances().isEmpty(),
                "and no instances either: " + data.getInstances());
    }

    @Test
    void subscriptionOnlyClientIsNeverAdvertisedAsAChange() throws Exception {
        cm.register("watcher", "10.0.0.9", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "watcher");

        protocol.requestSyncChange("watcher");

        // The coalescing window is 200ms; poll well past it.
        long deadline = System.currentTimeMillis() + 3000;
        while (transport.sends.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        List<RecordingTransport.Send> sends = transport.sends;
        assertEquals(1, sends.size(), "exactly one announcement to the peer (per peer): " + sends);
        RecordingTransport.Send send = sends.get(0);
        assertEquals(DistroProtocol.OP_DELETE, send.op(),
                "an empty payload must be announced as a removal, never as a CHANGE — "
                        + "a CHANGE here would install a replica shell on the peer");
        assertEquals(0, send.content().length, "a removal carries no body");
    }

    @Test
    void replicaNeverBecomesAPushTargetOnThisNode() {
        cm.register("watcher", "10.0.0.9", "3.0.0", Map.of(), noop());
        storage.addSubscriber(NS, GROUP, SVC, "watcher");

        // A publisher client from another node arrives as a replica.
        storage.applyClientSyncData(new ClientSyncData("remote-pub",
                List.of(KEY.toKeyString()), List.of(instance("10.0.0.8", 8080)), 3L));

        assertEquals(1, storage.getInstances(NS, GROUP, SVC).size(), "precondition: routable");
        assertFalse(storage.getSubscriberConnections(KEY).contains("remote-pub"),
                "the push-target index must list only connections this node actually holds: "
                        + storage.getSubscriberConnections(KEY));
        // The local subscriber survives the replica sync untouched.
        assertTrue(storage.getSubscriberConnections(KEY).contains("watcher"),
                "the node's own subscriber must still be the push target");
    }

    // ========================================================================

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
}
