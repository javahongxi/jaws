package org.hongxi.jaws.registry.harbor;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.configcenter.DynamicConfiguration;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.HarborServer;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The harbor config SPI against a real server, pinned to the nacos leg's
 * shape: the extension loads by protocol, config keeps its OWN connection
 * apart from naming, and each key is its own dataId under JAWS_CONFIG —
 * publishes, remote changes and removal-as-null all reach the framework's
 * per-key listeners.
 *
 * @author shenhongxi
 */
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HarborDynamicConfigurationTest {

    private static HarborServer harborServer;
    private static int port;
    private static int baselineConnections;
    private static HarborClient namingClient;
    private static DynamicConfiguration dc;

    @BeforeAll
    static void startServer(@TempDir Path dataDir) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URL url = new URL("harbor", "127.0.0.1", port, "");
        url.addParameter(HarborServer.PARAM_DATA_DIR, dataDir.toString());
        harborServer = new HarborServer(url);
        harborServer.start();
        awaitTrue(() -> harborServer.getRaftProtocol().raftNode().isLeader(), 5_000);
        baselineConnections = harborServer.getConnectionManager().size();
    }

    @AfterAll
    static void stopServer() {
        if (namingClient != null) {
            namingClient.close();
        }
        if (harborServer != null) {
            harborServer.close();
        }
    }

    @Test
    @Order(1)
    void spiLoadsByProtocolAndConfigKeepsItsOwnConnection() throws Exception {
        dc = ExtensionLoader.getExtensionLoader(DynamicConfiguration.class)
                .getExtension(JawsConstants.REGISTRY_PROTOCOL_HARBOR);
        assertTrue(dc instanceof HarborDynamicConfiguration,
                "the harbor protocol must resolve to its own DynamicConfiguration");

        // One connection for naming, a separate one for config — the same
        // separation the nacos leg keeps between NamingService and ConfigService.
        namingClient = new HarborClient("127.0.0.1", port);
        dc.init(new URL("harbor", "127.0.0.1", port, ""));
        awaitTrue(() -> harborServer.getConnectionManager().size() == baselineConnections + 2, 5_000);
    }

    @Test
    @Order(2)
    void publishReadAndRemoteChangesReachTheListener() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        dc.addListener("spi.round", (key, value) -> received.add(value));
        dc.setConfig("spi.round", "v1");
        assertEquals("v1", dc.getConfig("spi.round"));
        awaitTrue(() -> received.contains("v1"), 5_000);

        try (HarborClient writer = new HarborClient("127.0.0.1", port)) {
            writer.publishConfig("spi.round", HarborProtocol.JAWS_CONFIG_GROUP, "v2");
            awaitTrue(() -> received.contains("v2"), 5_000);
        }
        assertEquals("v2", dc.getConfig("spi.round"));
    }

    @Test
    @Order(3)
    void remoteRemovalReachesTheListenerAsNull() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        dc.addListener("spi.gone", (key, value) -> received.add(value));
        dc.setConfig("spi.gone", "here");
        awaitTrue(() -> received.contains("here"), 5_000);

        try (HarborClient writer = new HarborClient("127.0.0.1", port)) {
            writer.removeConfig("spi.gone", HarborProtocol.JAWS_CONFIG_GROUP);
            awaitTrue(() -> !received.isEmpty()
                    && received.get(received.size() - 1) == null, 5_000);
        }
        assertNull(dc.getConfig("spi.gone"), "the local cache must let the removal through");
    }

    @Test
    @Order(4)
    void unlistenStopsCallbacks() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        dc.addListener("spi.quiet", (key, value) -> received.add(value));
        dc.setConfig("spi.quiet", "steady");
        awaitTrue(() -> received.contains("steady"), 5_000);

        dc.removeListener("spi.quiet", null);
        try (HarborClient writer = new HarborClient("127.0.0.1", port)) {
            writer.publishConfig("spi.quiet", HarborProtocol.JAWS_CONFIG_GROUP, "nobody-home");
            Thread.sleep(400);
            // The leading null is the registration replay of the then-missing
            // key; after unlisten nothing further may arrive.
            assertEquals(Arrays.asList(null, "steady"), received, "an unlistened key must stay silent");
        }
    }

    @Test
    @Order(5)
    void lateListenerAndReadSeeWhatAnotherClientCommitted() throws Exception {
        try (HarborClient writer = new HarborClient("127.0.0.1", port)) {
            writer.publishConfig("spi.late", HarborProtocol.JAWS_CONFIG_GROUP, "born-elsewhere");
            writer.publishConfig("spi.late.listen", HarborProtocol.JAWS_CONFIG_GROUP, "heard-elsewhere");
        }
        // No local cache entry yet: the read must go to the server.
        assertEquals("born-elsewhere", dc.getConfig("spi.late"));

        // A listener armed after a remote commit still learns the current
        // value: arming the harbor listen replays it at registration.
        List<String> received = new CopyOnWriteArrayList<>();
        dc.addListener("spi.late.listen", (key, value) -> received.add(value));
        awaitTrue(() -> received.contains("heard-elsewhere"), 5_000);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

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