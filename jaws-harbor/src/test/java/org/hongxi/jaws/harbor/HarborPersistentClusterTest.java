package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for persistent instances on a 3-node harbor cluster over
 * the real wire transport: a write accepted by a follower is forwarded to the
 * leader and replicated to every node, and a leader death keeps the committed
 * data and re-opens the group for new writes after re-election.
 * <p>
 * Election timings are shrunk through the URL so failover completes in well
 * under a second; the management API is disabled to keep the three nodes from
 * fighting over {@code grpcPort + 10}.
 *
 * @author shenhongxi
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HarborPersistentClusterTest {

    private static final List<HarborServer> nodes = new ArrayList<>();
    private static final List<Integer> ports = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        String dataDir = HarborTestDataDirs.fresh();
        for (int i = 0; i < 3; i++) {
            ports.add(freePort());
        }
        for (int port : ports) {
            URL url = new URL("harbor", "127.0.0.1", port, "");
            url.addParameter(HarborServer.PARAM_DATA_DIR, dataDir);
            url.addParameter("httpApiPort", "0");
            url.addParameter("raftElectionTimeoutMinMs", "300");
            url.addParameter("raftElectionTimeoutMaxMs", "600");
            url.addParameter("raftHeartbeatMs", "100");
            nodes.add(new HarborServer(url));
        }
        // Full membership before start: RaftNode captures its peers at start().
        for (HarborServer node : nodes) {
            String selfAddress = node.getClusterManager().getSelfAddress();
            for (int peerPort : ports) {
                String peerAddress = "127.0.0.1:" + peerPort;
                if (!peerAddress.equals(selfAddress)) {
                    node.addClusterMember(peerAddress);
                }
            }
        }
        for (HarborServer node : nodes) {
            node.start();
        }
        // A follower only learns the leader from its first heartbeat, so readiness
        // means every node can name the leader — not just that one exists.
        await(() -> leaderCount() == 1 && nodes.stream()
                        .allMatch(node -> node.getRaftProtocol().raftNode().leaderId() != null),
                "one raft leader must emerge and be known to every node", 10_000);
    }

    @AfterAll
    static void tearDown() {
        for (HarborServer node : nodes) {
            node.close();
        }
    }

    @Test
    @Order(1)
    void aWriteThroughAFollowerReplicatesToEveryNode() throws Exception {
        HarborServer follower = nodes.stream()
                .filter(node -> !node.getRaftProtocol().raftNode().isLeader())
                .findFirst().orElseThrow();
        int followerPort = portOf(follower);

        Instance kept = instance("127.0.0.1", 9600);
        Instance dropped = instance("127.0.0.1", 9601);
        try (HarborClient client = new HarborClient(
                HarborClientConfig.of("127.0.0.1", followerPort))) {
            client.registerInstance("cp-cluster", kept);
            client.registerInstance("cp-cluster-dropped", dropped);
        }
        for (HarborServer node : nodes) {
            await(() -> node.getServiceStorage()
                            .getInstances("public", "DEFAULT_GROUP", "cp-cluster").size() == 1,
                    "every node must apply the forwarded REGISTER", 5_000);
            assertFalse(node.getServiceStorage()
                            .getInstances("public", "DEFAULT_GROUP", "cp-cluster").get(0).isEphemeral(),
                    "the replicated instance stays persistent");
        }

        // Deregistration rides the same forwarded path and shrinks every replica.
        try (HarborClient client = new HarborClient(
                HarborClientConfig.of("127.0.0.1", followerPort))) {
            client.deregisterInstance("cp-cluster-dropped", dropped);
        }
        for (HarborServer node : nodes) {
            await(() -> node.getServiceStorage()
                            .getInstances("public", "DEFAULT_GROUP", "cp-cluster-dropped").isEmpty(),
                    "every node must apply the forwarded DEREGISTER", 5_000);
        }
    }

    @Test
    @Order(2)
    void leaderDeathKeepsTheDataAndReopensForWrites() throws Exception {
        HarborServer dead = leader();
        int deadPort = portOf(dead);
        dead.close();
        nodes.remove(dead);

        await(() -> leaderCount() == 1, "the survivors must elect a replacement", 10_000);
        HarborServer newLeader = leader();
        assertFalse(portOf(newLeader) == deadPort, "the replacement must be a survivor");

        // The committed registration from the first test outlives its leader.
        for (HarborServer survivor : nodes) {
            await(() -> survivor.getServiceStorage()
                            .getInstances("public", "DEFAULT_GROUP", "cp-cluster").size() == 1,
                    "the survivors must still hold the committed instance", 5_000);
        }

        // And the new leader accepts fresh writes that replicate to the survivor.
        try (HarborClient client = new HarborClient(
                HarborClientConfig.of("127.0.0.1", portOf(newLeader)))) {
            client.registerInstance("cp-after-failover", instance("127.0.0.1", 9602));
        }
        for (HarborServer survivor : nodes) {
            await(() -> survivor.getServiceStorage()
                            .getInstances("public", "DEFAULT_GROUP", "cp-after-failover").size() == 1,
                    "the new leader's write must replicate to every survivor", 5_000);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static long leaderCount() {
        return nodes.stream()
                .filter(node -> node.getRaftProtocol().raftNode().isLeader()).count();
    }

    private static HarborServer leader() {
        return nodes.stream()
                .filter(node -> node.getRaftProtocol().raftNode().isLeader())
                .findFirst().orElseThrow();
    }

    private static int portOf(HarborServer node) {
        return Integer.parseInt(node.getClusterManager().getSelfAddress().split(":")[1]);
    }

    private static Instance instance(String ip, int port) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setEphemeral(false);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setWeight(1.0);
        return instance;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, String message, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }
}