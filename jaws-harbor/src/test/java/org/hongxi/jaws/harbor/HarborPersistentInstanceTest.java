package org.hongxi.jaws.harbor;

import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for persistent instances on a single-node harbor: the
 * client-facing CP path end to end — registration and deregistration through
 * the Raft log, the no-mixing guard in both directions, durability across a
 * server restart on the same data directory, and the leader-only TCP health
 * probe flipping the committed health flag.
 *
 * @author shenhongxi
 */
class HarborPersistentInstanceTest {

    @Test
    void registerAndDeregisterThroughTheCpLog() throws Exception {
        int port = freePort();
        HarborServer server = startServer(port, HarborTestDataDirs.fresh());
        try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port))) {
            Instance persistent = instance("127.0.0.1", 9500, false);
            client.registerInstance("cp-round-trip", persistent);

            List<Instance> stored = server.getServiceStorage()
                    .getInstances("public", "DEFAULT_GROUP", "cp-round-trip");
            assertEquals(1, stored.size(), "the committed REGISTER must be readable: " + stored);
            assertFalse(stored.get(0).isEphemeral(), "the stored instance stays persistent");

            client.deregisterInstance("cp-round-trip", persistent);
            assertTrue(server.getServiceStorage()
                            .getInstances("public", "DEFAULT_GROUP", "cp-round-trip").isEmpty(),
                    "the committed DEREGISTER must remove the instance");
        } finally {
            server.close();
        }
    }

    @Test
    void mixingPublishersOfDifferentKindsIsRefusedBothWays() throws Exception {
        int port = freePort();
        HarborServer server = startServer(port, HarborTestDataDirs.fresh());
        try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port))) {
            // Persistent first: an ephemeral publisher must not join the service.
            client.registerInstance("cp-first", instance("127.0.0.1", 9501, false));
            JawsServiceException ephemeralRefused = assertThrows(JawsServiceException.class,
                    () -> client.registerInstance("cp-first", instance("127.0.0.1", 9502, true)));
            assertTrue(ephemeralRefused.getMessage().contains("mixing is not allowed"),
                    ephemeralRefused.getMessage());
            assertEquals(1, server.getServiceStorage()
                    .getInstances("public", "DEFAULT_GROUP", "cp-first").size(),
                    "the refused ephemeral registration must not have reached storage");

            // Ephemeral first: a persistent publisher must not join either.
            client.registerInstance("ap-first", instance("127.0.0.1", 9503, true));
            JawsServiceException persistentRefused = assertThrows(JawsServiceException.class,
                    () -> client.registerInstance("ap-first", instance("127.0.0.1", 9504, false)));
            assertTrue(persistentRefused.getMessage().contains("mixing is not allowed"),
                    persistentRefused.getMessage());
            List<Instance> apFirst = server.getServiceStorage()
                    .getInstances("public", "DEFAULT_GROUP", "ap-first");
            assertEquals(1, apFirst.size(), "the refused persistent registration changed nothing");
            assertTrue(apFirst.get(0).isEphemeral());
        } finally {
            server.close();
        }
    }

    @Test
    void persistentInstancesSurviveAServerRestart() throws Exception {
        int port = freePort();
        String dataDir = HarborTestDataDirs.fresh();
        HarborServer server = startServer(port, dataDir);
        Instance persistent = instance("127.0.0.1", 9505, false);
        try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port))) {
            client.registerInstance("cp-restart", persistent);
        }
        server.close();

        // Same port, same data directory: the log on disk is the only memory.
        HarborServer restarted = startServer(port, dataDir);
        try {
            await(() -> !restarted.getServiceStorage()
                            .getInstances("public", "DEFAULT_GROUP", "cp-restart").isEmpty(),
                    "the restarted node must re-apply its committed log", 5_000);
            List<Instance> recovered = restarted.getServiceStorage()
                    .getInstances("public", "DEFAULT_GROUP", "cp-restart");
            assertEquals(9505, recovered.get(0).getPort());
            assertFalse(recovered.get(0).isEphemeral());

            // The recovered node must also accept new writes, not just replay old ones.
            try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port))) {
                client.registerInstance("cp-restart-new", instance("127.0.0.1", 9506, false));
            }
            assertEquals(1, restarted.getServiceStorage()
                    .getInstances("public", "DEFAULT_GROUP", "cp-restart-new").size());
        } finally {
            restarted.close();
        }
    }

    @Test
    void theLeaderProbeFlipsHealthWithTheAddress() throws Exception {
        int port = freePort();
        int targetPort = freePort();
        HarborServer server = startServer(port, HarborTestDataDirs.fresh());
        try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port))) {
            // Registered healthy, but nothing listens: the probe must demote it.
            client.registerInstance("cp-probed", instance("127.0.0.1", targetPort, false));
            await(() -> !healthOf(server, "cp-probed"),
                    "the probe must flip a dead address to unhealthy", 15_000);

            // The address comes back: the next probe round must promote it again.
            try (ServerSocket revived = new ServerSocket(targetPort)) {
                await(() -> healthOf(server, "cp-probed"),
                        "the probe must flip a revived address back to healthy", 15_000);
            }
        } finally {
            server.close();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static boolean healthOf(HarborServer server, String serviceName) {
        List<Instance> instances = server.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", serviceName);
        return !instances.isEmpty() && instances.get(0).isHealthy();
    }

    private static Instance instance(String ip, int port, boolean ephemeral) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setEphemeral(ephemeral);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setWeight(1.0);
        return instance;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The management API is disabled so parallel test servers never fight over ports. */
    private static HarborServer startServer(int port, String dataDir) throws InterruptedException {
        URL url = new URL("harbor", "0.0.0.0", port, "");
        url.addParameter(HarborServer.PARAM_DATA_DIR, dataDir);
        url.addParameter("httpApiPort", "0");
        HarborServer server = new HarborServer(url);
        server.start();
        await(() -> server.getRaftProtocol().raftNode().isLeader(),
                "the single-node raft group never elected itself", 5_000);
        return server;
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
}