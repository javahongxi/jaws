package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.model.request.DynamicConfigChangeRequest;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A config broadcast triggered on one node must reach clients attached to its
 * peers.
 * <p>
 * {@code broadcastConfigChange} pushes to the connections of the node it runs
 * on, so in a cluster the HTTP entry has to be relayed: the writing node
 * forwards the change to its peers, and each peer pushes to the clients it
 * holds itself. Pushing to local clients is a per-node behaviour by design —
 * matching nacos, where a node only ever notifies the listeners registered
 * with it, after its own dump completes.
 * <p>
 * The relayed request is cluster-face traffic like Distro's, and it must not
 * loop: a peer that received the relay pushes locally and never re-forwards,
 * so a client attached to the triggering node sees the change exactly once.
 *
 * @author shenhongxi
 */
@Timeout(60)
class ConfigBroadcastClusterTest {

    @Test
    void aBroadcastOnOneNodeReachesClientsAttachedToPeersExactlyOnce() throws Exception {
        NodePair pair = startPair();
        try (HarborClient clientOnA = new HarborClient("127.0.0.1", pair.portA);
             HarborClient clientOnB = new HarborClient("127.0.0.1", pair.portB)) {
            List<DynamicConfigChangeRequest> seenOnA = new CopyOnWriteArrayList<>();
            List<DynamicConfigChangeRequest> seenOnB = new CopyOnWriteArrayList<>();
            clientOnA.setDynamicConfigListener(seenOnA::add);
            clientOnB.setDynamicConfigListener(seenOnB::add);
            awaitTrue(() -> pair.nodeA.getConnectionManager().allConnections().size() >= 1
                    && pair.nodeB.getConnectionManager().allConnections().size() >= 1);

            pair.nodeA.broadcastConfigChange("db.url", "jdbc:mysql://new", false);

            awaitTrue(() -> seenOnB.stream().anyMatch(r -> "db.url".equals(r.getKey())),
                    "the client attached to the peer must receive the broadcast");
            DynamicConfigChangeRequest received = seenOnB.stream()
                    .filter(r -> "db.url".equals(r.getKey())).findFirst().orElseThrow();
            assertEquals("jdbc:mysql://new", received.getValue());
            assertFalse(received.isDeleted());

            // Loop guard: the peer pushed locally and did not relay back, so the
            // client on the triggering node saw the change exactly once.
            awaitTrue(() -> seenOnA.size() >= 1);
            awaitStableCount(seenOnA, 1);
        } finally {
            pair.nodeA.close();
            pair.nodeB.close();
        }
    }

    @Test
    void deletionBroadcastsRelayToo() throws Exception {
        NodePair pair = startPair();
        try (HarborClient clientOnB = new HarborClient("127.0.0.1", pair.portB)) {
            List<DynamicConfigChangeRequest> seenOnB = new CopyOnWriteArrayList<>();
            clientOnB.setDynamicConfigListener(seenOnB::add);
            awaitTrue(() -> pair.nodeB.getConnectionManager().allConnections().size() >= 1);

            pair.nodeA.broadcastConfigChange("feature.flag", null, true);

            awaitTrue(() -> seenOnB.stream().anyMatch(r ->
                    "feature.flag".equals(r.getKey()) && r.isDeleted()));
        } finally {
            pair.nodeA.close();
            pair.nodeB.close();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Two nodes wired as each other's cluster member, with their serving ports. */
    private static final class NodePair {
        final HarborServer nodeA;
        final HarborServer nodeB;
        final int portA;
        final int portB;

        NodePair(HarborServer nodeA, HarborServer nodeB, int portA, int portB) {
            this.nodeA = nodeA;
            this.nodeB = nodeB;
            this.portA = portA;
            this.portB = portB;
        }
    }

    /**
     * Two nodes wired as each other's cluster member, on fresh free ports.
     * Retries the whole pair when a bind loses the freePort race (CI flake).
     */
    private NodePair startPair() throws Exception {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int portA = freePort();
            int portB = freePort();
            HarborServer nodeA = node(portA, portB);
            HarborServer nodeB = node(portB, portA);
            try {
                nodeA.start();
                nodeB.start();
                return new NodePair(nodeA, nodeB, portA, portB);
            } catch (RuntimeException e) {
                last = e;
                nodeA.close();
            }
        }
        throw last;
    }

    private HarborServer node(int selfPort, int peerPort) throws Exception {
        URL url = new URL("harbor", "0.0.0.0", selfPort, "");
        url.addParameter(HarborServer.PARAM_CLUSTER_MEMBERS, "127.0.0.1:" + peerPort);
        HarborServer server = new HarborServer(url);
        server.start();
        awaitListening(selfPort);
        return server;
    }

    /** Poll until satisfied, then keep sampling to confirm the count stays put. */
    private static void awaitStableCount(List<?> list, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (list.size() > expected) {
                fail("expected a stable count of " + expected + " but observed " + list.size()
                        + " — the broadcast is looping");
            }
            Thread.sleep(50);
        }
        assertEquals(expected, list.size(), "count drifted within the stability window");
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        awaitTrue(condition, "condition not satisfied");
    }

    private static void awaitTrue(BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message + " within 5000ms");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Poll the port until it accepts, instead of guessing a startup delay. */
    private static void awaitListening(int listenPort) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", listenPort), 200);
                return;
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }
        fail("harbor port " + listenPort + " never accepted a connection");
    }
}
