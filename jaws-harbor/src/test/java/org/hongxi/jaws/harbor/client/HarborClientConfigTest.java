package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.harbor.HarborServer;
import org.hongxi.jaws.harbor.HarborTestDataDirs;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The native SDK's config face against a real harbor server: the listen round
 * (query-then-listen with the held md5), change pushes, removal-as-null, and
 * the reconnect replay that re-arms a listen on the fresh connection.
 * <p>
 * The reconnect cases are the point of the redo table: harbor retires every
 * listen the dead connection owned, so a client that does not replay would
 * simply stop hearing about changes — silently, with no error anywhere.
 *
 * @author shenhongxi
 */
@Timeout(120)
class HarborClientConfigTest {

    private static HarborServer harborServer;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URL url = new URL("harbor", "127.0.0.1", port, "");
        url.addParameter(HarborServer.PARAM_DATA_DIR, HarborTestDataDirs.fresh());
        harborServer = new HarborServer(url);
        harborServer.start();
        awaitTrue(() -> harborServer.getRaftProtocol().raftNode().isLeader(), 5_000);
    }

    @AfterAll
    static void stopServer() {
        if (harborServer != null) {
            harborServer.close();
        }
    }

    @Test
    void listenerLearnsTheCurrentValueThenEveryUpdate() throws Exception {
        try (HarborClient client = newClient()) {
            client.publishConfig("sdk-watched", "first");
            List<String> received = new CopyOnWriteArrayList<>();
            client.addConfigListener("sdk-watched", received::add);
            // Subscribing late still hands over the current value at once.
            awaitTrue(() -> received.contains("first"), 5_000);

            client.publishConfig("sdk-watched", "second");
            awaitTrue(() -> received.contains("second"), 5_000);
            assertEquals("second", client.getConfig("sdk-watched"));
        }
    }

    @Test
    void removalReachesTheListenerAsNullContent() throws Exception {
        try (HarborClient client = newClient()) {
            List<String> received = new CopyOnWriteArrayList<>();
            client.addConfigListener("sdk-removed", received::add);
            awaitTrue(() -> received.contains(null), 5_000);

            client.publishConfig("sdk-removed", "here");
            awaitTrue(() -> received.contains("here"), 5_000);

            client.removeConfig("sdk-removed");
            awaitTrue(() -> received.size() >= 2
                    && received.get(received.size() - 1) == null, 5_000);
            assertNull(client.getConfig("sdk-removed"));
        }
    }

    @Test
    void unlistenStopsCallbacksAndTheEntryIsCollected() throws Exception {
        try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port)
                .withRedoDelayMillis(200))) {
            List<String> received = new CopyOnWriteArrayList<>();
            Consumer<String> listener = received::add;
            client.addConfigListener("sdk-unlistened", listener);
            awaitTrue(() -> received.contains(null), 5_000);

            client.removeConfigListener("sdk-unlistened", listener);
            // The spent entry is collected by the redo pass, not by the call.
            awaitTrue(() -> client.configListenCount() == 0, 5_000);

            int seen = received.size();
            client.publishConfig("sdk-unlistened", "nobody-home");
            Thread.sleep(400);
            assertEquals(seen, received.size(), "an unlistened key must stay silent");
        }
    }

    @Test
    void reconnectReplaysTheListenSoChangesStillArrive() throws Exception {
        try (HarborClient client = newClient()) {
            List<String> received = new CopyOnWriteArrayList<>();
            client.addConfigListener("sdk-replayed", received::add);
            awaitTrue(() -> received.contains(null), 5_000);
            client.publishConfig("sdk-replayed", "before");
            awaitTrue(() -> received.contains("before"), 5_000);

            // The closure transaction on the server drops this connection's
            // listens; only the replay re-arms them on the fresh session.
            client.connection().recover();
            awaitTrue(client.connection()::isConnected, 5_000);

            client.publishConfig("sdk-replayed", "after");
            awaitTrue(() -> received.contains("after"), 5_000);
        }
    }

    @Test
    void reconnectWithoutAChangeStaysSilentToListeners() throws Exception {
        try (HarborClient client = newClient()) {
            client.publishConfig("sdk-quiet", "steady");
            List<String> received = new CopyOnWriteArrayList<>();
            client.addConfigListener("sdk-quiet", received::add);
            awaitTrue(() -> received.contains("steady"), 5_000);

            int seen = received.size();
            // The replay carries the held md5; the server reports nothing changed,
            // so a reconnect must not re-notify what nobody touched.
            client.connection().recover();
            awaitTrue(client.connection()::isConnected, 5_000);
            Thread.sleep(400);
            assertEquals(seen, received.size(), "an unchanged key must not re-notify");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static HarborClient newClient() {
        return new HarborClient("127.0.0.1", port);
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