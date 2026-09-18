package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Losing a harbor node must cost a client nothing but a moment.
 *
 * <p>The two nodes here are deliberately <em>not</em> a joined cluster: nothing is
 * replicated between them, so anything the second node knows after the failover,
 * the client put there itself. That is the property worth pinning — recovery is a
 * replay of what the client owns, not a favour done by the cluster.
 *
 * @author shenhongxi
 */
@Timeout(120)
class ClientFailoverSemanticsTest {

    private HarborServer serverA;
    private HarborServer serverB;
    private int portA;
    private int portB;

    @AfterEach
    void stopServers() {
        if (serverA != null) {
            serverA.close();
        }
        if (serverB != null) {
            serverB.close();
        }
    }

    @Test
    void aDeadNodeMovesTheClientToAnotherOneWhichThenHoldsItsState() throws Exception {
        startTwoNodes();
        try (HarborClient client = new HarborClient(HarborClientConfig
                .ofCluster("127.0.0.1:" + portA + ",127.0.0.1:" + portB)
                .withKeepAliveMillis(300))) {
            client.registerInstance("survives", instance("127.0.0.1", 9700));
            var seen = new CopyOnWriteArrayList<org.hongxi.jaws.harbor.model.ServiceInfo>();
            client.subscribe("survives", seen::add);

            // Where it landed is up to the random start, so read it rather than assume
            // the first configured node.
            awaitTrue(() -> sizeOn(serverA, "survives") == 1 || sizeOn(serverB, "survives") == 1,
                    5_000);
            HarborServer attached = sizeOn(serverA, "survives") == 1 ? serverA : serverB;
            HarborServer standby = attached == serverA ? serverB : serverA;
            assertEquals(0, sizeOn(standby, "survives"), "one home node at a time");

            attached.close();
            if (attached == serverA) {
                serverA = null;
            } else {
                serverB = null;
            }

            awaitTrue(() -> sizeOn(standby, "survives") == 1, 20_000);
            // Queries follow the client's new home rather than failing on the old one.
            awaitTrue(() -> client.getInstances("survives").size() == 1, 5_000);

            // And the subscription came with it: a change on the new node is pushed.
            seen.clear();
            int standbyPort = standby == serverA ? portA : portB;
            try (HarborClient publisher = new HarborClient("127.0.0.1", standbyPort)) {
                publisher.registerInstance("survives", instance("127.0.0.1", 9701));
                awaitTrue(() -> seen.stream().anyMatch(each -> hostsOf(each).contains(9701)),
                        10_000);
            }
        }
    }

    @Test
    void aLoneUnreachableNodeFailsLoudlyInsteadOfHanging() throws Exception {
        // One address in the list and it is dead: the client has nowhere to fail over
        // to, so it must say so on the first attempt rather than keep the caller
        // waiting through a retry storm.
        int deadPort = freePort();
        Exception thrown = assertThrows(Exception.class,
                () -> new HarborClient(HarborClientConfig.of("127.0.0.1", deadPort)));
        String message = String.valueOf(rootOf(thrown));
        assertTrue(message.contains(String.valueOf(deadPort)),
                "the failure has to name the node that could not be reached: " + message);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void startTwoNodes() throws Exception {
        portA = freePort();
        portB = freePort();
        serverA = new HarborServer(new URL("harbor", "0.0.0.0", portA, ""));
        serverA.start();
        serverB = new HarborServer(new URL("harbor", "0.0.0.0", portB, ""));
        serverB.start();
        Thread.sleep(500);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The wire layer wraps its own cause; the address is what the caller needs. */
    private static Throwable rootOf(Throwable error) {
        Throwable current = error;
        StringBuilder text = new StringBuilder();
        while (current != null) {
            text.append(current.getMessage()).append(' ');
            current = current.getCause() == current ? null : current.getCause();
        }
        return new Throwable(text.toString());
    }

    private static int sizeOn(HarborServer server, String service) {
        if (server == null) {
            return 0;
        }
        return server.getServiceStorage().getInstances("public", "DEFAULT_GROUP", service).size();
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
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException e) {
                // A call that fails because the node is gone is the normal shape of
                // this wait; the client heals it, and the next poll asks again.
            }
            Thread.sleep(50);
        }
        fail("condition not satisfied within " + timeoutMs + "ms");
    }
}
