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

import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The config face against a real nacos-client {@link ConfigService}: publish,
 * query, listen-push and removal all ride the standard nacos config protocol,
 * and a committed config outlives the server process through the raft log.
 * <p>
 * The watcher registers with the default (blank) tenant, so the push path here
 * also pins the namespace-transfer rule: the server stores the config under
 * {@code public} but must notify this watcher with a null tenant, or the
 * client's cache lookup misses and the change never surfaces.
 *
 * @author shenhongxi
 */
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigCenterTest {

    private static HarborServer harborServer;
    private static int port;
    private static String dataDir;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        // Harbor answers naming and config on one port; nacos-client must not
        // add its usual +1000 gRPC offset.
        System.setProperty("nacos.server.grpc.port.offset", "0");
        dataDir = HarborTestDataDirs.fresh();
        harborServer = startNode();
    }

    @AfterAll
    static void stopServer() {
        if (harborServer != null) {
            harborServer.close();
        }
        System.clearProperty("nacos.server.grpc.port.offset");
    }

    @Test
    @Order(1)
    void publishQueryAndRemoveThroughNacosClient() throws Exception {
        ConfigService config = createConfigService();
        try {
            assertTrue(config.publishConfig("nacos-round-trip", "DEFAULT_GROUP", "v1"));
            awaitTrue(() -> "v1".equals(tryGet(config, "nacos-round-trip")), 8_000);

            assertTrue(config.publishConfig("nacos-round-trip", "DEFAULT_GROUP", "v2"));
            awaitTrue(() -> "v2".equals(tryGet(config, "nacos-round-trip")), 8_000);

            assertTrue(config.removeConfig("nacos-round-trip", "DEFAULT_GROUP"));
            awaitTrue(() -> tryGet(config, "nacos-round-trip") == null, 8_000);
        } finally {
            config.shutDown();
        }
    }

    @Test
    @Order(2)
    void listenerLearnsChangesAndRemovalAsNull() throws Exception {
        ConfigService config = createConfigService();
        try {
            List<String> received = new CopyOnWriteArrayList<>();
            config.addListener("nacos-watched", "DEFAULT_GROUP", new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String content) {
                    received.add(content);
                }
            });
            // nacos-client arms a fresh listener with the cache's current md5,
            // so an absent config stays silent until something actually moves.
            config.publishConfig("nacos-watched", "DEFAULT_GROUP", "watched-value");
            awaitTrue(() -> received.contains("watched-value"), 8_000);

            // A removal reaches the same listener as null content — the nacos
            // CONFIG_NOT_FOUND semantics, not a stale value and not silence.
            config.removeConfig("nacos-watched", "DEFAULT_GROUP");
            awaitTrue(() -> received.size() >= 2
                    && received.get(received.size() - 1) == null, 8_000);
        } finally {
            config.shutDown();
        }
    }

    @Test
    @Order(3)
    void committedConfigSurvivesAServerRestart() throws Exception {
        ConfigService config = createConfigService();
        try {
            config.publishConfig("nacos-durable", "DEFAULT_GROUP", "kept-across-restart");
            awaitTrue(() -> "kept-across-restart".equals(tryGet(config, "nacos-durable")), 8_000);
        } finally {
            config.shutDown();
        }

        // Same port, same dataDir: the raft log replays into memory on start.
        harborServer.close();
        harborServer = startNode();

        ConfigService reborn = createConfigService();
        try {
            awaitTrue(() -> "kept-across-restart".equals(tryGet(reborn, "nacos-durable")), 10_000);
            assertEquals("kept-across-restart",
                    harborServer.getConfigStorage()
                            .get("nacos-durable", "DEFAULT_GROUP", "public").getContent(),
                    "the restarted node must hold the committed config in memory");
        } finally {
            reborn.shutDown();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static HarborServer startNode() throws Exception {
        URL url = new URL("harbor", "127.0.0.1", port, "");
        url.addParameter(HarborServer.PARAM_DATA_DIR, dataDir);
        HarborServer node = new HarborServer(url);
        node.start();
        awaitRaftLeader(node);
        return node;
    }

    private static ConfigService createConfigService() throws Exception {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + port);
        return NacosFactory.createConfigService(props);
    }

    /** Query without throwing: a checked client error reads as "not there yet". */
    private static String tryGet(ConfigService config, String dataId) {
        try {
            return config.getConfig(dataId, "DEFAULT_GROUP", 3_000);
        } catch (Exception e) {
            return null;
        }
    }

    /** A single-node group elects itself at start; wait for that before proposing. */
    private static void awaitRaftLeader(HarborServer node) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (node.getRaftProtocol().raftNode().isLeader()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("the single-node raft group never elected itself");
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("condition not satisfied within " + timeoutMs + "ms");
    }
}