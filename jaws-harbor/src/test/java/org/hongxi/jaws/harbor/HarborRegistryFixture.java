package org.hongxi.jaws.harbor;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * One harbor server plus one real nacos-client per test class, and the client
 * bookkeeping to close them again. Shared by the semantic test classes so each
 * of them can stay about the one contract it pins.
 *
 * <p>Not a test itself: the name keeps it out of surefire's way.
 *
 * @author shenhongxi
 */
abstract class HarborRegistryFixture {

    protected static final String GROUP = "DEFAULT_GROUP";

    protected static HarborServer harborServer;
    protected static int port;
    protected static NamingService nacosNaming;

    private final List<HarborClient> opened = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        // A nacos-client derives the gRPC port from its server address; offset 0 keeps
        // both implementations on the same port we actually bound.
        System.setProperty("nacos.server.grpc.port.offset", "0");
        harborServer = new HarborServer(new URL("harbor", "0.0.0.0", port, ""));
        harborServer.start();
        awaitListening(port);

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
    void closeOpenedClients() {
        // Closing a client ends its session, and with it every registration and
        // subscription it held — which is also how the next class gets a clean server.
        opened.forEach(HarborClient::close);
        opened.clear();
    }

    protected HarborClient newClient() {
        HarborClient client = new HarborClient("127.0.0.1", port);
        opened.add(client);
        return client;
    }

    /** Register one instance from its own client, as a separate provider would. */
    protected void register(String service, String ip, int listenPort, String cluster,
                            boolean enabled) {
        register(service, GROUP, ip, listenPort, cluster, enabled);
    }

    protected void register(String service, String group, String ip, int listenPort,
                            String cluster, boolean enabled) {
        org.hongxi.jaws.harbor.model.Instance instance =
                new org.hongxi.jaws.harbor.model.Instance();
        instance.setIp(ip);
        instance.setPort(listenPort);
        instance.setClusterName(cluster);
        instance.setEnabled(enabled);
        newClient().registerInstance(service, group, instance);
    }

    /** A direct query from the other implementation, with no client-side cache. */
    protected static List<Instance> nacosQuery(String service) {
        try {
            return nacosNaming.getAllInstances(service, GROUP, false);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** nacos-client filters clusters on its own side, so this checks the data. */
    protected static List<Instance> nacosSelectInCluster(String service, String cluster,
                                                         boolean healthy) {
        try {
            return nacosNaming.selectInstances(service, GROUP, List.of(cluster), healthy, false);
        } catch (Exception e) {
            return List.of();
        }
    }

    protected static List<Integer> portsOf(
            List<org.hongxi.jaws.harbor.model.Instance> hosts) {
        return hosts.stream().map(org.hongxi.jaws.harbor.model.Instance::getPort).toList();
    }

    protected static List<Integer> hostsOf(org.hongxi.jaws.harbor.model.ServiceInfo info) {
        return info.getHosts() == null
                ? List.of()
                : info.getHosts().stream()
                        .map(org.hongxi.jaws.harbor.model.Instance::getPort).toList();
    }

    protected static void awaitTrue(BooleanSupplier condition, long timeoutMs)
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
    /**
     * Wait until the port accepts a connection instead of sleeping a fixed guess:
     * {@code HarborServer.start()} binds before it returns, so any blind delay here is
     * either wasted on a fast machine or too short on a loaded one.
     */
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
