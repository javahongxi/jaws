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

            // Session identity is per TCP connection and recovery reused the
            // channel, so the id legitimately survives. What cannot survive on its
            // own is the session's content: ending the old stream runs harbor's
            // closure transaction, which removes this connection's instances and
            // subscribers. So everything asserted below came back through the
            // replay, and is not state that was merely left behind.
            assertEquals(1, nativeConnectionIds().size());
            assertEquals(connectionIdBefore, connectionIdAfter);
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
     * The redo pass, not the caller, collects a spent entry — Nacos defers it the
     * same way. Asserting both halves matters: removal at call time would leak
     * nothing but also prove nothing about the pass, while a pass that never runs
     * turns every deregister into a permanent table entry.
     */
    @Test
    void redoPassCollectsSpentEntries() throws Exception {
        try (HarborClient client = new HarborClient(
                HarborClientConfig.of("127.0.0.1", port).withRedoDelayMillis(1_000))) {
            client.registerInstance("spent-entry", instance("127.0.0.1", 9900));
            awaitTrue(() -> !nacosInstances("spent-entry").isEmpty(), 5_000);

            client.deregisterInstance("spent-entry", instance("127.0.0.1", 9900));
            awaitTrue(() -> nacosInstances("spent-entry").isEmpty(), 5_000);
            assertEquals(1, client.registrationCount(),
                    "a confirmed deregister leaves the entry for the pass, it does not remove it");

            awaitTrue(() -> client.registrationCount() == 0, 5_000);

            // And it stays gone across a recovery: nothing was left owed.
            client.connection().recover();
            Thread.sleep(1_500);
            assertTrue(nacosInstances("spent-entry").isEmpty());
            assertEquals(0, client.registrationCount());
        }
    }

    @Test
    void redoPassCollectsSpentSubscriptions() throws Exception {
        try (HarborClient client = new HarborClient(
                HarborClientConfig.of("127.0.0.1", port).withRedoDelayMillis(1_000))) {
            Consumer<ServiceInfo> listener = info -> { };
            client.subscribe("spent-subscription", listener);
            assertEquals(1, client.subscriptionCount());

            client.unsubscribe("spent-subscription", listener);
            awaitTrue(() -> client.subscriptionCount() == 0, 5_000);
        }
    }

    /**
     * Harbor's health tiers are calibrated against a 5s beat
     * (unhealthy past ~3 beats, connection retired past ~18), and a v2 client
     * sends no beat — so the keep-alive message is what holds an idle provider's
     * instances up. The window here is deliberately longer than the 15s tier.
     */
    @Test
    void keepAliveHoldsEphemeralInstancesHealthyAcrossIdleWindow() throws Exception {
        try (HarborClient client = new HarborClient(
                HarborClientConfig.of("127.0.0.1", port).withKeepAliveMillis(1_000))) {
            client.registerInstance("idle-provider", instance("127.0.0.1", 9500));
            awaitTrue(() -> !client.getInstances("idle-provider").isEmpty(), 5_000);

            Thread.sleep(20_000);

            List<Instance> healthy = client.getInstances("idle-provider", true);
            assertEquals(1, healthy.size());
            assertTrue(healthy.get(0).isHealthy(),
                    "keep-alive failed to hold the instance past the unhealthy tier");
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
            Thread.sleep(1_500);
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
            Thread.sleep(2_000);
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
