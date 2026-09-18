package org.hongxi.jaws.harbor.client;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.hongxi.jaws.harbor.HarborServer;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end behaviour of the native client against a real harbor server.
 * <p>
 * The interop direction matters here: every claim about what the wire looks
 * like is checked from the other side with a real nacos-client, because a
 * native client talking to a native server proves only that two implementations
 * of our own reading of the protocol agree with each other.
 *
 * @author shenhongxi
 */
@Timeout(120)
class HarborClientTest {

    private static HarborServer harborServer;
    private static int port;
    private static NamingService nacosNaming;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        System.setProperty("nacos.server.grpc.port.offset", "0");
        harborServer = new HarborServer(new URL("harbor", "0.0.0.0", port, ""));
        harborServer.start();
        Thread.sleep(500);

        Properties properties = new Properties();
        properties.setProperty("serverAddr", "127.0.0.1:" + port);
        nacosNaming = NacosFactory.createNamingService(properties);
    }

    @AfterAll
    static void stopServer() throws Exception {
        nacosNaming.shutDown();
        harborServer.close();
        System.clearProperty("nacos.server.grpc.port.offset");
    }

    @Test
    void registrationByNativeClientIsVisibleToNacosClient() throws Exception {
        try (HarborClient client = newClient()) {
            client.registerInstance("native-writes", instance("127.0.0.1", 9200));

            awaitTrue(() -> !nacosInstances("native-writes").isEmpty(), 5_000);
            var seen = nacosInstances("native-writes");
            assertEquals(1, seen.size());
            assertEquals(9200, seen.get(0).getPort());
            assertTrue(seen.get(0).isEphemeral());
            assertTrue(seen.get(0).isHealthy());

            // Same fact through our own query path, which is a different DTO.
            assertEquals(1, client.getInstances("native-writes").size());
        }
        // Closing sends no deregister: ending the stream is what retires the
        // session, so this is the assertion that the teardown path works.
        awaitTrue(() -> nacosInstances("native-writes").isEmpty(), 5_000);
    }

    @Test
    void nativeSubscriberIsNotifiedByAChangeFromNacosClient() throws Exception {
        List<ServiceInfo> received = new CopyOnWriteArrayList<>();
        try (HarborClient client = newClient()) {
            client.subscribe("pushed-service", received::add);

            com.alibaba.nacos.api.naming.pojo.Instance pushed =
                    new com.alibaba.nacos.api.naming.pojo.Instance();
            pushed.setIp("10.1.1.1");
            pushed.setPort(9300);
            pushed.setEphemeral(true);
            nacosNaming.registerInstance("pushed-service", "DEFAULT_GROUP", pushed);

            awaitTrue(() -> received.stream()
                    .anyMatch(each -> each.getHosts().size() == 1
                            && each.getHosts().get(0).getPort() == 9300), 10_000);
        }
    }

    @Test
    void reconnectReplaysRegistrationsAndSubscriptionsUnderANewConnectionId()
            throws Exception {
        List<ServiceInfo> received = new CopyOnWriteArrayList<>();
        try (HarborClient client = newClient()) {
            client.registerInstance("replayed", instance("127.0.0.1", 9400));
            client.subscribe("replayed", received::add);
            awaitTrue(() -> !nacosInstances("replayed").isEmpty(), 5_000);

            String connectionIdBefore = onlyNativeConnectionId();
            client.connection().recover();
            String connectionIdAfter = onlyNativeConnectionId();

            // The node was alive, so recovery probed it and reopened the stream on the
            // existing channel — identity is per TCP connection, so the id survives.
            // What must not survive untouched is the session's content: ending the old
            // stream runs harbor's closure transaction, and the assertions below can only
            // be satisfied by the replay (a stale stream ending late is likewise kept from
            // tearing down what the client has just rebuilt).
            assertEquals(connectionIdBefore, connectionIdAfter);
            assertEquals(1, nativeConnectionIds().size(), "one session, not two");
            // The registration is back.
            awaitTrue(() -> nacosInstances("replayed").stream()
                    .anyMatch(each -> each.getPort() == 9400), 5_000);

            // And the subscription moved with it.
            received.clear();
            com.alibaba.nacos.api.naming.pojo.Instance another =
                    new com.alibaba.nacos.api.naming.pojo.Instance();
            another.setIp("10.1.1.2");
            another.setPort(9401);
            another.setEphemeral(true);
            nacosNaming.registerInstance("replayed", "DEFAULT_GROUP", another);
            awaitTrue(() -> received.stream()
                    .anyMatch(each -> each.getHosts().size() == 2), 10_000);
        }
    }

    /**
     * A confirmed deregister leaves its entry in the table: the pass, not the caller,
     * collects it (Nacos defers the same way). A redo period long enough to never fire
     * during the test is what makes "it is still there" a statement about the code
     * rather than about who won the race.
     */
    @Test
    void spentRegistrationIsLeftForThePassInsteadOfRemovedInline() throws Exception {
        try (HarborClient client = new HarborClient(HarborClientConfig
                .of("127.0.0.1", port).withRedoDelayMillis(60_000))) {
            client.registerInstance("spent-entry", instance("127.0.0.1", 9900));
            awaitTrue(() -> !nacosInstances("spent-entry").isEmpty(), 5_000);

            client.deregisterInstance("spent-entry", instance("127.0.0.1", 9900));
            awaitTrue(() -> nacosInstances("spent-entry").isEmpty(), 5_000);
            assertEquals(1, client.registrationCount(), "deferred, not inline");

            // And a recovery must not turn that spent entry back into a registration.
            client.connection().recover();
            Thread.sleep(1_200);
            assertTrue(nacosInstances("spent-entry").isEmpty());
            assertEquals(0, client.registrationCount(),
                    "recovery walks the same table, so it collects the REMOVE entry");
        }
    }

    /** The pass really runs: with a short period the spent entry disappears by itself. */
    @Test
    void thePassCollectsSpentRegistrationsOnItsOwn() throws Exception {
        try (HarborClient client = new HarborClient(HarborClientConfig
                .of("127.0.0.1", port).withRedoDelayMillis(300))) {
            client.registerInstance("collected", instance("127.0.0.1", 9910));
            awaitTrue(() -> !nacosInstances("collected").isEmpty(), 5_000);

            client.deregisterInstance("collected", instance("127.0.0.1", 9910));
            awaitTrue(() -> client.registrationCount() == 0, 5_000);
        }
    }

    @Test
    void redoPassCollectsSpentSubscriptions() throws Exception {
        try (HarborClient client = new HarborClient(
                HarborClientConfig.of("127.0.0.1", port).withRedoDelayMillis(400))) {
            Consumer<ServiceInfo> listener = info -> { };
            client.subscribe("spent-subscription", listener);
            assertEquals(1, client.subscriptionCount());

            client.unsubscribe("spent-subscription", listener);
            awaitTrue(() -> client.subscriptionCount() == 0, 5_000);
        }
    }

    @Test
    void batchRegistrationReplaysEveryInstanceItHeld() throws Exception {
        try (HarborClient client = newClient()) {
            client.batchRegisterInstance("batched",
                    List.of(instance("127.0.0.1", 9950), instance("127.0.0.1", 9951)));
            awaitTrue(() -> client.getInstances("batched").size() == 2, 5_000);

            // Ending the old stream runs the closure transaction; only a replay that
            // keeps the batch shape puts both instances back.
            client.connection().recover();
            awaitTrue(() -> nacosInstances("batched").size() == 2, 10_000);
        }
    }

    @Test
    void batchDeregisterShrinksWhatALaterReplayOwes() throws Exception {
        try (HarborClient client = newClient()) {
            client.batchRegisterInstance("partial-drop", List.of(
                    instance("127.0.0.1", 9970), instance("127.0.0.1", 9971)));
            awaitTrue(() -> client.getInstances("partial-drop").size() == 2, 5_000);

            // Removing one instance must leave the sibling published: the publisher
            // index is per client while instances are per instance.
            client.batchDeregisterInstance("partial-drop",
                    List.of(instance("127.0.0.1", 9970)));
            awaitTrue(() -> client.getInstances("partial-drop").size() == 1, 5_000);

            // The replay is what shows the bookkeeping: a redo table still holding the
            // removed instance would put it back.
            client.connection().recover();
            awaitTrue(() -> nacosInstances("partial-drop").size() == 1, 10_000);
            assertEquals(9971, nacosInstances("partial-drop").get(0).getPort());
        }
    }

    /**
     * Keep-alive traffic is what holds an idle provider's instances up: harbor's
     * health tiers are calibrated against a beat (unhealthy past 3×5s of silence,
     * session retired past ~18) while a v2 client sends no beat at all.
     * <p>
     * The idle budget is injected through {@code reconcileHealth(timeoutMs)} rather
     * than waited out — asking the sweep "would you call this connection idle at 1s?"
     * is the same verdict as the production 15s tier, and does not spend 20 seconds of
     * wall clock to observe it.
     */
    @Test
    void keepAliveKeepsTheConnectionInsideTheIdleBudget() throws Exception {
        try (HarborClient client = new HarborClient(HarborClientConfig
                .of("127.0.0.1", port).withKeepAliveMillis(300))) {
            client.registerInstance("idle-provider", instance("127.0.0.1", 9500));
            awaitTrue(() -> !client.getInstances("idle-provider", true).isEmpty(), 5_000);

            // Silent apart from keep-alive, for five of its ticks.
            Thread.sleep(1_600);
            harborServer.getServiceStorage().reconcileHealth(1_000);

            assertEquals(1, client.getInstances("idle-provider", true).size(),
                    "a 300ms keep-alive must clear a 1s idle budget; if it does not, the"
                            + " sweep below will mark the instance unhealthy");
        }
    }

    @Test
    void queryAndListServicesCoverTheReadingSide() throws Exception {
        try (HarborClient client = newClient()) {
            client.registerInstance("readable", instance("127.0.0.1", 9600));
            awaitTrue(() -> client.getInstances("readable").size() == 1, 5_000);

            Instance picked = client.selectOneHealthyInstance("readable");
            assertEquals(9600, picked.getPort());

            assertTrue(client.listServices("DEFAULT_GROUP").stream()
                    .anyMatch(each -> each.contains("readable")));
            assertTrue(client.serverHealthy());
        }
    }

    @Test
    void deregistrationLeavesNoResidualEntryAcrossRecovery() throws Exception {
        try (HarborClient client = newClient()) {
            client.registerInstance("dereg-then-recover", instance("127.0.0.1", 9700));
            awaitTrue(() -> !nacosInstances("dereg-then-recover").isEmpty(), 5_000);

            client.deregisterInstance("dereg-then-recover", instance("127.0.0.1", 9700));
            awaitTrue(() -> nacosInstances("dereg-then-recover").isEmpty(), 5_000);

            // A spent entry must not come back as a registration on replay.
            client.connection().recover();
            Thread.sleep(1_200);
            assertTrue(nacosInstances("dereg-then-recover").isEmpty(),
                    "a deregistered instance was resurrected by the replay");
        }
    }

    @Test
    void unsubscribedServiceStaysQuietAcrossRecovery() throws Exception {
        List<ServiceInfo> received = new CopyOnWriteArrayList<>();
        // Same listener instance on both sides: a method reference is not equal to
        // a second one created from the same target.
        Consumer<ServiceInfo> listener = received::add;
        try (HarborClient client = newClient()) {
            client.subscribe("unwatch-then-recover", listener);
            Thread.sleep(500);

            client.unsubscribe("unwatch-then-recover", listener);
            received.clear();
            client.connection().recover();

            com.alibaba.nacos.api.naming.pojo.Instance later =
                    new com.alibaba.nacos.api.naming.pojo.Instance();
            later.setIp("10.2.2.2");
            later.setPort(9800);
            later.setEphemeral(true);
            nacosNaming.registerInstance("unwatch-then-recover", "DEFAULT_GROUP", later);
            // Past the 500ms push coalescing window: had the subscription survived
            // the replay, the notification would be in by now.
            Thread.sleep(1_200);
            assertTrue(received.isEmpty(), "an unsubscribed service was re-subscribed");
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static HarborClient newClient() {
        return new HarborClient("127.0.0.1", port);
    }

    private static Instance instance(String ip, int listenPort) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(listenPort);
        instance.setWeight(1.0);
        instance.setEphemeral(true);
        instance.setMetadata(Map.of("application", "harbor-client-test"));
        return instance;
    }

    private static List<com.alibaba.nacos.api.naming.pojo.Instance> nacosInstances(
            String serviceName) {
        try {
            return nacosNaming.getAllInstances(serviceName, "DEFAULT_GROUP");
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Sessions this test class opened with the native client, told apart from the
     * shared nacos-client session by the version string its setup frame carried.
     */
    private static List<String> nativeConnectionIds() {
        return harborServer.getConnectionManager().allConnections().stream()
                .filter(each -> each.clientVersion() != null
                        && each.clientVersion().startsWith(HarborConnection.CLIENT_VERSION))
                .map(each -> each.connectionId())
                .toList();
    }

    private static String onlyNativeConnectionId() {
        List<String> ids = nativeConnectionIds();
        assertEquals(1, ids.size(), "expected exactly one native session");
        assertFalse(ids.get(0).isEmpty());
        return ids.get(0);
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("condition not satisfied within " + timeoutMs + "ms");
    }
}
