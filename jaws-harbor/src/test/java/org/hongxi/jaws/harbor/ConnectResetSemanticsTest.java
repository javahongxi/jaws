package org.hongxi.jaws.harbor;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The server's order to reconnect: what it costs the client, and what the client
 * owes the server in return.
 * <p>
 * Two directions are pinned here. The client must comply — reconnect and put back
 * everything the dead session owned — and it must say so, because the expelling node
 * uses that answer to decide whether to close the session itself. A redirect is the
 * reason the order exists: shed load, take a node out of service.
 *
 * @author shenhongxi
 */
@Timeout(120)
class ConnectResetSemanticsTest {

    private static HarborServer serverA;
    private static HarborServer serverB;
    private static int portA;
    private static int portB;

    private HarborClient client;

    @BeforeAll
    static void startServers() throws Exception {
        portA = freePort();
        portB = freePort();
        serverA = new HarborServer(new URL("harbor", "0.0.0.0", portA, ""));
        serverA.start();
        serverB = new HarborServer(new URL("harbor", "0.0.0.0", portB, ""));
        serverB.start();
        awaitListening(portA);
        awaitListening(portB);
    }

    @AfterAll
    static void stopServers() {
        serverA.close();
        serverB.close();
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void anExpelledClientReconnectsAndPutsItsStateBack() throws Exception {
        client = new HarborClient("127.0.0.1", portA);
        client.registerInstance("expelled", instance("127.0.0.1", 9950));
        var seen = new CopyOnWriteArrayList<org.hongxi.jaws.harbor.model.ServiceInfo>();
        client.subscribe("expelled", seen::add);
        awaitTrue(() -> client.getInstances("expelled").size() == 1, 5_000);

        String connectionId = onlyConnectionId(serverA);
        assertTrue(serverA.expelConnection(connectionId, null, null),
                "a compliant client answers within the ack window");

        // Same TCP connection, so the same connection id comes back — which is exactly
        // why the closure triggered by the old stream must not delete what the replay
        // has just put back.
        awaitTrue(() -> client.getInstances("expelled").size() == 1, 10_000);
        assertEquals(1, serverA.getConnectionManager().size(), "one session, not two");

        // And the subscription survived with it: a change now reaches the watcher.
        seen.clear();
        try (HarborClient publisher = new HarborClient("127.0.0.1", portA)) {
            publisher.registerInstance("expelled", instance("127.0.0.1", 9951));
            awaitTrue(() -> seen.stream()
                    .anyMatch(each -> hostsOf(each).contains(9951)), 10_000);
        }
    }

    @Test
    void aRedirectMovesTheClientAndEverythingItOwnsToTheOtherNode() throws Exception {
        client = new HarborClient("127.0.0.1", portA);
        client.registerInstance("moved", instance("127.0.0.1", 9960));
        awaitTrue(() -> client.getInstances("moved").size() == 1, 5_000);

        String connectionId = onlyConnectionId(serverA);
        assertTrue(serverA.expelConnection(connectionId, "127.0.0.1", String.valueOf(portB)),
                "the client acks the order before it goes");

        awaitTrue(() -> serverB.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "moved").size() == 1, 10_000);
        assertEquals(0, serverA.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "moved").size(),
                "the node that shed the client holds nothing of it");
        assertEquals(0, serverA.getConnectionManager().size());

        // Queries now answer from the new home, through the same client object.
        awaitTrue(() -> client.getInstances("moved").size() == 1, 5_000);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String onlyConnectionId(HarborServer server) {
        List<ConnectionManager.ConnectionRecord> connections =
                List.copyOf(server.getConnectionManager().allConnections());
        assertEquals(1, connections.size(), "one client session expected");
        return connections.get(0).connectionId();
    }

    private static org.hongxi.jaws.harbor.model.Instance instance(String ip, int listenPort) {
        org.hongxi.jaws.harbor.model.Instance instance =
                new org.hongxi.jaws.harbor.model.Instance();
        instance.setIp(ip);
        instance.setPort(listenPort);
        return instance;
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
