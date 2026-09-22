package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.StreamSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A notification stream that ends late must not tear down the session that replaced
 * it, because connection identity is the TCP connection and a reconnect over the
 * same channel keeps that identity.
 * <p>
 * Without the guard, the sequence end-old-stream → set-up-new → replay-registers
 * can be reordered on the server's threads so that the closure transaction deletes
 * what the client just put back — the client cannot see it happen, only that its
 * instances keep disappearing.
 *
 * @author shenhongxi
 */
class StaleStreamClosureTest {

    private static final String CONN = "conn-1";
    private ConnectionManager cm;
    private ServiceStorage storage;
    private ConnectionCleanup cleanup;

    @BeforeEach
    void setUp() {
        cm = new ConnectionManager();
        storage = new ServiceStorage(cm, key -> { });
        ClusterManager cluster = new ClusterManager(new URL("harbor", "127.0.0.1", 19848, ""));
        DistroProtocol distro = new DistroProtocol(cluster, new NoopTransport(), storage, cm);
        cleanup = new ConnectionCleanup(cm, storage, distro);
    }

    @Test
    void aStaleStreamEndLeavesTheReEstablishedSessionAndItsDataAlone() {
        StreamSubject<Message> staleStream = silentSubject();
        cm.register(CONN, "127.0.0.1", "jaws-test", java.util.Map.of(), staleStream);

        // The client reconnects on the same TCP connection: same id, new stream, and
        // then replays what it owns onto that new session.
        StreamSubject<Message> liveStream = silentSubject();
        cm.register(CONN, "127.0.0.1", "jaws-test", java.util.Map.of(), liveStream);
        storage.registerInstance("public", "DEFAULT_GROUP", "svc", instance(9002), CONN);

        assertFalse(cleanup.cleanup(CONN, staleStream),
                "the stream that ended is not the session's current one");
        assertEquals(1, storage.getInstances("public", "DEFAULT_GROUP", "svc").size(),
                "what the client replayed onto the live session must survive");
        assertTrue(cm.getClientSession(CONN) != null, "the session stays registered");

        // The stream that does own the session still closes it.
        assertTrue(cleanup.cleanup(CONN, liveStream));
        assertEquals(0, storage.getInstances("public", "DEFAULT_GROUP", "svc").size());
    }

    @Test
    void anUnknownConnectionIsStillClosedIdempotently() {
        assertTrue(cleanup.cleanup("never-registered", silentSubject()),
                "nothing to guard against — the unconditional path is free to run");
    }

    private static Instance instance(int port) {
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(port);
        instance.setInstanceId("127.0.0.1:" + port);
        return instance;
    }

    private static StreamSubject<Message> silentSubject() {
        return new StreamSubject<>() {
            @Override public void onNext(Message item) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
    }

    private static final class NoopTransport implements HarborNodeTransport {
        @Override public boolean syncData(String addr, String cid, String op, byte[] content) {
            return false;
        }
        @Override public List<String> syncVerify(String addr, List<ClientVerifyInfo> infos) {
            return List.of();
        }
        @Override public boolean syncConfigBroadcast(String a, org.hongxi.jaws.harbor.model.request.ConfigBroadcastSyncRequest r) { return true; }

        @Override public byte[] getSnapshot(String addr) {
            return new byte[0];
        }
        @Override public void shutdown() { }
    }
}
