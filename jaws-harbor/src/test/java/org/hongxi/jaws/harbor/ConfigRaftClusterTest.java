package org.hongxi.jaws.harbor;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Config replication on a 3-node harbor cluster over the real wire transport:
 * the HTTP write entrance on a follower commits through the raft log, every
 * node's apply converges its own ConfigStorage, and a nacos-client watcher
 * attached to a DIFFERENT node is notified by that node's own apply — no
 * inter-node push relay exists anymore.
 * <p>
 * Each node keeps its management API on a dedicated free port (the default
 * {@code grpcPort + 10} would collide between neighbours). Readiness is the
 * same criterion as the persistent-cluster test: every node can name the leader.
 *
 * @author shenhongxi
 */
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigRaftClusterTest {

    private static final List<HarborServer> nodes = new ArrayList<>();
    private static final List<Integer> ports = new ArrayList<>();
    private static final List<Integer> httpPorts = new ArrayList<>();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() throws Exception {
        System.setProperty("nacos.server.grpc.port.offset", "0");
        String dataDir = HarborTestDataDirs.fresh();
        for (int i = 0; i < 3; i++) {
            ports.add(freePort());
            httpPorts.add(freePort());
        }
        for (int i = 0; i < 3; i++) {
            URL url = new URL("harbor", "127.0.0.1", ports.get(i), "");
            url.addParameter(HarborServer.PARAM_DATA_DIR, dataDir);
            url.addParameter("httpApiPort", String.valueOf(httpPorts.get(i)));
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
        System.clearProperty("nacos.server.grpc.port.offset");
    }

    @Test
    @Order(1)
    void httpWriteOnAFollowerConvergesEveryNode() throws Exception {
        int followerIndex = followerIndex();
        String reply = postConfig(httpPorts.get(followerIndex), "db.url", "jdbc:mysql://cluster");
        assertTrue(reply.contains("\"success\":true"), "the write must commit: " + reply);

        for (HarborServer node : nodes) {
            await(() -> {
                ConfigItem item = node.getConfigStorage().get(
                        "db.url", HarborProtocol.JAWS_CONFIG_GROUP, "public");
                return item != null && item.getContent().equals("jdbc:mysql://cluster");
            }, "every node must apply the forwarded CONFIG_PUBLISH", 5_000);
        }

        // The read window shows the same committed state on every node.
        for (int httpPort : httpPorts) {
            String listing = get(httpPort, "/api/configs");
            assertTrue(listing.contains("db.url"),
                    "GET /api/configs must list the committed key: " + listing);
        }
    }

    @Test
    @Order(2)
    void watcherOnAnotherNodeIsNotifiedByItsOwnNodesApply() throws Exception {
        int writerIndex = followerIndex();
        // Watch from a node other than the one whose HTTP entrance takes the write.
        int watcherIndex = (writerIndex + 1) % nodes.size();
        ConfigService config = createConfigService(ports.get(watcherIndex));
        try {
            List<String> received = new CopyOnWriteArrayList<>();
            config.addListener("db.url", HarborProtocol.JAWS_CONFIG_GROUP, listener(received));
            // A watcher attached late still learns what test 1 already committed.
            awaitTrue(() -> received.contains("jdbc:mysql://cluster"), 10_000);

            List<String> flags = new CopyOnWriteArrayList<>();
            config.addListener("feature.flag", HarborProtocol.JAWS_CONFIG_GROUP, listener(flags));
            postConfig(httpPorts.get(writerIndex), "feature.flag", "on");
            awaitTrue(() -> flags.contains("on"), 10_000);

            // Per-key storage on the watcher's own node: the new key lands as
            // its own dataId beside the old one, not merged into it.
            ConfigItem flag = nodes.get(watcherIndex).getConfigStorage().get(
                    "feature.flag", HarborProtocol.JAWS_CONFIG_GROUP, "public");
            assertEquals("on", flag.getContent());
            ConfigItem db = nodes.get(watcherIndex).getConfigStorage().get(
                    "db.url", HarborProtocol.JAWS_CONFIG_GROUP, "public");
            assertEquals("jdbc:mysql://cluster", db.getContent(),
                    "a write to one key must not touch another");
        } finally {
            config.shutDown();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static int followerIndex() {
        for (int i = 0; i < nodes.size(); i++) {
            if (!nodes.get(i).getRaftProtocol().raftNode().isLeader()) {
                return i;
            }
        }
        throw new IllegalStateException("no follower in the cluster");
    }

    private static long leaderCount() {
        return nodes.stream()
                .filter(node -> node.getRaftProtocol().raftNode().isLeader()).count();
    }

    private static String postConfig(int httpPort, String key, String value) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort
                        + "/api/config?key=" + key + "&value=" + value))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "the HTTP write entrance must answer 200");
        return response.body();
    }

    private static String get(int httpPort, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + httpPort + path))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static Listener listener(List<String> sink) {
        return new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String content) {
                sink.add(content);
            }
        };
    }

    private static ConfigService createConfigService(int grpcPort) throws Exception {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + grpcPort);
        return NacosFactory.createConfigService(props);
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

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        await(condition, "condition not satisfied within " + timeoutMs + "ms", timeoutMs);
    }
}