package org.hongxi.jaws.harbor;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.model.ConfigCasConflictException;
import org.hongxi.jaws.rpc.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The compare-and-swap publish face: the casMd5 is judged in raft apply order,
 * so concurrent writers holding the same md5 serialise and exactly one wins.
 * The refusal is pinned to the exact nacos message text — clients (nacos-client
 * included) recognise the conflict by that string, no dedicated errorCode ever
 * reaching the wire.
 * <p>
 * Also pins the two nacos CAS rules harbor must keep: an absent key is created
 * regardless of the casMd5 (a first publish cannot conflict), and a blank
 * casMd5 writes unconditionally. Unlike nacos, a winning CAS is readable at
 * once: harbor applies before replying, with no dump-cache window.
 *
 * @author shenhongxi
 */
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigCasSemanticsTest {

    private static final String GROUP = "DEFAULT_GROUP";
    private static final String TENANT = "public";

    private static HarborServer harborServer;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        System.setProperty("nacos.server.grpc.port.offset", "0");
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
        System.clearProperty("nacos.server.grpc.port.offset");
    }

    @Test
    @Order(1)
    void firstPublishWithAnyCasMd5CreatesTheKey() throws Exception {
        ConfigService config = createConfigService();
        try {
            // The nacos insertOrUpdateCas rule: no stored row means create,
            // the casMd5 is ignored — a first publish can never conflict.
            assertTrue(config.publishConfigCas("cas-fresh", GROUP, "v1",
                    "0123456789abcdef0123456789abcdef"));
            awaitTrue(() -> "v1".equals(tryGet(config, "cas-fresh")), 8_000);
        } finally {
            config.shutDown();
        }
    }

    @Test
    @Order(2)
    void casWithTheCurrentMd5WinsAndIsReadableAtOnce() {
        try (HarborClient client = newClient()) {
            client.publishConfig("cas-win", GROUP, TENANT, "v1");
            client.publishConfig("cas-win", GROUP, TENANT, "v2", ConfigItem.md5("v1"));
            // No await: harbor applies before the reply, so the new value is
            // already in memory — the nacos dump-cache window does not exist here.
            assertEquals("v2", client.getConfig("cas-win", GROUP, TENANT));
            assertEquals("v2", harborServer.getConfigStorage()
                    .get("cas-win", GROUP, TENANT).getContent());
        }
    }

    @Test
    @Order(3)
    void staleCasLosesWithTheExactNacosRefusal() throws Exception {
        try (HarborClient client = newClient()) {
            client.publishConfig("cas-stale", GROUP, TENANT, "base");
            ConfigCasConflictException conflict = assertThrows(ConfigCasConflictException.class,
                    () -> client.publishConfig("cas-stale", GROUP, TENANT, "loser",
                            ConfigItem.md5("not-base")));
            assertEquals(ConfigCasConflictException.MESSAGE, conflict.getMessage(),
                    "the refusal text is the wire signal clients match on");
            assertEquals("base", client.getConfig("cas-stale", GROUP, TENANT),
                    "a lost CAS must leave the value untouched");
        }
        // The nacos-client leg reads the same refusal as a plain false.
        ConfigService config = createConfigService();
        try {
            assertFalse(config.publishConfigCas("cas-stale", GROUP, "loser",
                    ConfigItem.md5("not-base")));
            assertEquals("base", tryGet(config, "cas-stale"));
        } finally {
            config.shutDown();
        }
    }

    @Test
    @Order(4)
    void concurrentCasWritersSerialiseAndExactlyOneWins() throws Exception {
        try (HarborClient client = newClient()) {
            client.publishConfig("cas-race", GROUP, TENANT, "base");
            String baseMd5 = ConfigItem.md5("base");

            int writers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            AtomicReference<String> winner = new AtomicReference<>();
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < writers; i++) {
                    String content = "writer-" + i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        try {
                            // Every writer holds the SAME md5: only the first
                            // entry in apply order may pass the check.
                            client.publishConfig("cas-race", GROUP, TENANT, content, baseMd5);
                            wins.incrementAndGet();
                            winner.set(content);
                        } catch (ConfigCasConflictException expected) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, wins.get(), "exactly one CAS writer may win");
            assertEquals(writers - 1, conflicts.get(), "every other writer must lose");
            assertEquals(winner.get(), client.getConfig("cas-race", GROUP, TENANT),
                    "the stored value must be the winner's, no lost update");
            assertEquals(ConfigItem.md5(winner.get()), harborServer.getConfigStorage()
                    .get("cas-race", GROUP, TENANT).getMd5());
        }
    }

    @Test
    @Order(5)
    void blankCasMd5WritesUnconditionally() {
        try (HarborClient client = newClient()) {
            client.publishConfig("cas-blank", GROUP, TENANT, "v1");
            client.publishConfig("cas-blank", GROUP, TENANT, "v2", "");
            assertEquals("v2", client.getConfig("cas-blank", GROUP, TENANT));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static HarborClient newClient() {
        return new HarborClient("127.0.0.1", port);
    }

    private static ConfigService createConfigService() throws Exception {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:" + port);
        return NacosFactory.createConfigService(props);
    }

    /** Query without throwing: a checked client error reads as "not there yet". */
    private static String tryGet(ConfigService config, String dataId) {
        try {
            return config.getConfig(dataId, GROUP, 3_000);
        } catch (Exception e) {
            return null;
        }
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