package org.hongxi.jaws.harbor;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the cluster, health and paging parameters actually do.
 * <p>
 * Each of these was once accepted on the wire and dropped on the floor, which is
 * worse than rejecting it: a caller that asked for one cluster and got all of
 * them cannot tell the difference. Every assertion here is about a visible
 * difference, and the cluster filter is checked from a real nacos-client too —
 * the field belongs to that protocol, not to us.
 *
 * @author shenhongxi
 */
@Timeout(90)
class ClusterAndPagingSemanticsTest {

    private static final String GROUP = "DEFAULT_GROUP";

    private static HarborServer harborServer;
    private static int port;
    private static NamingService nacosNaming;

    private final List<HarborClient> opened = new CopyOnWriteArrayList<>();

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

    @AfterEach
    void closeClients() {
        opened.forEach(HarborClient::close);
        opened.clear();
    }

    @Test
    void queryHonoursClusterAllowListAndDropsDisabledInstances() throws Exception {
        String service = "cluster-query";
        register(service, "127.0.0.1", 9001, "a", true);
        register(service, "127.0.0.1", 9002, "a", false);
        register(service, "127.0.0.1", 9003, "b", true);

        HarborClient reader = newClient();
        awaitTrue(() -> reader.getInstances(service, GROUP, false).size() == 2, 5_000);

        assertEquals(2, portsOf(reader.getInstances(service, GROUP, null, false)).size(),
                "no cluster asked for: both clusters, but the disabled instance is out");
        assertFalse(portsOf(reader.getInstances(service, GROUP, null, false)).contains(9002),
                "enableOnly is applied on a query, as in Nacos");

        assertEquals(List.of(9001),
                portsOf(reader.getInstances(service, GROUP, "a", false)), "single cluster");
        assertEquals(List.of(9001, 9003).stream().sorted().toList(),
                portsOf(reader.getInstances(service, GROUP, "a,b", false)).stream()
                        .sorted().toList(), "cluster allow-list is a union");
        assertEquals(List.of(), portsOf(reader.getInstances(service, GROUP, "c", false)),
                "an unknown cluster answers empty, not everything");

        // Seen from the other implementation. nacos-client's own selectInstances
        // filters locally, so the plain query path is what proves the server side:
        // both clusters present, the disabled one gone, and cluster intact per
        // instance — the field is named "cluster" on the wire, exactly once.
        awaitTrue(() -> nacosQuery(service).size() == 2, 5_000);
        assertEquals(List.of(9001, 9003), nacosQuery(service).stream()
                .map(Instance::getPort).sorted().toList());
        // nacos-client's own selectInstances takes a health *value*, not a flag:
        // healthy=true is what asks for the one live instance in cluster a.
        assertEquals(1, nacosSelectInCluster(service, "a", true).size(),
                "nacos-client's own view of the same data filters to one instance");
    }

    @Test
    void subscribeReplyAndPushesAreNarrowedPerSubscriber() throws Exception {
        String service = "cluster-subscribe";
        register(service, "127.0.0.1", 9101, "a", true);

        var seen = new CopyOnWriteArrayList<org.hongxi.jaws.harbor.model.ServiceInfo>();
        HarborClient watcher = newClient();
        watcher.subscribe(service, GROUP, "a", seen::add);

        awaitTrue(() -> !seen.isEmpty(), 5_000);
        assertEquals(List.of(9101), hostsOf(seen.get(seen.size() - 1)),
                "the subscribe reply is already cluster-filtered");

        // A change in a cluster this watcher did not ask for must not reach it.
        register(service, "127.0.0.1", 9102, "b", true);
        Thread.sleep(2_000);
        assertTrue(seen.stream().allMatch(each -> hostsOf(each).contains(9101)
                        && !hostsOf(each).contains(9102)),
                "a push leaked an instance from a cluster outside the filter");

        // A change inside the filter does reach it.
        register(service, "127.0.0.1", 9103, "a", true);
        awaitTrue(() -> seen.stream().anyMatch(each -> hostsOf(each).contains(9103)), 10_000);
    }

    @Test
    void instanceKeepsTheClusterItWasRegisteredIn() throws Exception {
        String service = "cluster-roundtrip";
        register(service, "127.0.0.1", 9201, "east", true);

        HarborClient reader = newClient();
        awaitTrue(() -> reader.getInstances(service, GROUP, null, false).size() == 1, 5_000);
        assertEquals("east",
                reader.getInstances(service, GROUP, null, false).get(0).getClusterName(),
                "clusterName survived registration and the read projection");
        assertEquals("east", nacosNaming.getAllInstances(service, GROUP).get(0).getClusterName(),
                "and is visible to a nacos-client");
    }

    @Test
    void unclusteredRegistrationFallsIntoTheDefaultCluster() throws Exception {
        String service = "cluster-default";
        // No cluster asked for: Nacos's client fills DEFAULT in, so harbor must agree
        // or a filter for "DEFAULT" would answer nothing.
        HarborClient provider = newClient();
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(9301);
        provider.registerInstance(service, toHarborInstance(instance));
        awaitTrue(() -> provider.getInstances(service, GROUP, "DEFAULT", false).size() == 1,
                5_000);
    }

    @Test
    void serviceListPagesAndReportsTheWholeMatchCount() throws Exception {
        // Its own group: paging counts are per group, and neighbouring tests leave
        // services behind in DEFAULT_GROUP.
        String group = "PAGING_GROUP";
        HarborClient reader = newClient();
        for (int i = 0; i < 3; i++) {
            register("paging-" + i, group, "127.0.0.1", 9400 + i, "a", true);
        }
        awaitTrue(() -> reader.listServices(group).size() == 3, 5_000);

        var first = reader.listServicesPage(group, 1, 2);
        assertEquals(2, first.names().size(), "page size is honoured");
        assertEquals(3, first.total(), "count is the whole match set, not the page");
        assertTrue(first.hasNextPage());

        var second = reader.listServicesPage(group, 2, 2);
        assertEquals(1, second.names().size(), "the tail page is trimmed, not padded");
        assertEquals(3, second.total());
        assertFalse(second.hasNextPage());

        var beyond = reader.listServicesPage(group, 9, 2);
        assertEquals(0, beyond.names().size(), "a start past the end answers empty");
        assertEquals(3, beyond.total(), "the total stays truthful on an empty page");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void register(String service, String ip, int listenPort, String cluster,
                          boolean enabled) {
        register(service, GROUP, ip, listenPort, cluster, enabled);
    }

    private void register(String service, String group, String ip, int listenPort,
                          String cluster, boolean enabled) {
        org.hongxi.jaws.harbor.model.Instance instance =
                new org.hongxi.jaws.harbor.model.Instance();
        instance.setIp(ip);
        instance.setPort(listenPort);
        instance.setClusterName(cluster);
        instance.setEnabled(enabled);
        newClient().registerInstance(service, group, instance);
    }

    private static org.hongxi.jaws.harbor.model.Instance toHarborInstance(Instance nacosForm) {
        org.hongxi.jaws.harbor.model.Instance instance =
                new org.hongxi.jaws.harbor.model.Instance();
        instance.setIp(nacosForm.getIp());
        instance.setPort(nacosForm.getPort());
        return instance;
    }

    /** A direct query from the other implementation; subscribe=false, so no cache. */
    private static List<Instance> nacosQuery(String service) {
        try {
            return nacosNaming.getAllInstances(service, GROUP, false);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * nacos-client filters clusters on its own side, so this checks the data it
     * reads rather than our server's filter.
     */
    private static List<Instance> nacosSelectInCluster(String service, String cluster,
                                                       boolean healthy) {
        try {
            return nacosNaming.selectInstances(service, GROUP, List.of(cluster), healthy, false);
        } catch (Exception e) {
            return List.of();
        }
    }

    private HarborClient newClient() {
        HarborClient client = new HarborClient("127.0.0.1", port);
        opened.add(client);
        return client;
    }

    private static List<Integer> portsOf(List<org.hongxi.jaws.harbor.model.Instance> hosts) {
        return hosts.stream().map(org.hongxi.jaws.harbor.model.Instance::getPort).toList();
    }

    private static List<Integer> hostsOf(org.hongxi.jaws.harbor.model.ServiceInfo info) {
        return info.getHosts() == null
                ? List.of()
                : info.getHosts().stream()
                        .map(org.hongxi.jaws.harbor.model.Instance::getPort).toList();
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
