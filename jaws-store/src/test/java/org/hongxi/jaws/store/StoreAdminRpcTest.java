package org.hongxi.jaws.store;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.store.client.StoreProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the admin query contract over the real jaws protocol. The
 * broker exports StoreService and StoreAdminService on one port; a producer
 * yields a physical offset that a StoreAdminService reference then reads back
 * directly from the CommitLog — proving the same-port coexistence, the
 * offset-based direct read, and the NOT_FOUND answer for a bogus offset.
 */
class StoreAdminRpcTest {

    @TempDir
    Path tempDir;

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ReferenceConfig<StoreAdminService> adminReference(String directUrl) {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization("fastjson2");

        ReferenceConfig<StoreAdminService> reference = new ReferenceConfig<>();
        reference.setInterface(StoreAdminService.class);
        reference.setApplication("jaws-store-admin");
        reference.setModule("store");
        reference.setGroup(StoreBroker.GROUP);
        reference.setVersion(StoreBroker.VERSION);
        reference.setRequestTimeout(3000);
        reference.setDirectUrl(directUrl);
        reference.setProtocol(protocolConfig);
        return reference;
    }

    @Test
    void viewMessageByOffsetOverJawsProtocol() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client").toString());
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve("store").toString());
        config.setHoldScanIntervalMillis(200);

        int port = freePort();
        StoreBroker broker = new StoreBroker(config, port);
        broker.start();
        String directUrl = "127.0.0.1:" + port;
        StoreProducer producer = new StoreProducer(directUrl);
        ReferenceConfig<StoreAdminService> adminRef = adminReference(directUrl);
        StoreAdminService admin = adminRef.getRef();
        try {
            String body = "admin-view-target";
            PutResult put = producer.sendSync(StoreMessage.of("admin-demo", 0, body));
            assertEquals(PutStatus.PUT_OK, put.getStatus());

            // hit: the physical offset returned by the put reads back the record
            AdminViewResult found = admin.viewMessageAsync(put.getCommitLogOffset()).get();
            assertTrue(found.isFound(), "committed offset should resolve to a record");
            assertEquals(ViewStatus.FOUND, found.getStatus());
            assertNotNull(found.getMessage());
            assertEquals(body, found.getMessage().bodyAsString());
            assertEquals("admin-demo", found.getMessage().getTopic());
            assertEquals(put.getCommitLogOffset(), found.getMessage().getCommitLogOffset());

            // NOT_FOUND: an offset far beyond the log maps to no loaded file
            AdminViewResult missing = admin.viewMessageAsync(Long.MAX_VALUE / 2).get();
            assertFalse(missing.isFound(), "a bogus offset must not resolve to a record");
            assertEquals(ViewStatus.NOT_FOUND, missing.getStatus());
        } finally {
            adminRef.destroy();
            producer.shutdown();
            broker.shutdown();
            if (previous == null) {
                System.clearProperty("jaws.store.client.path");
            } else {
                System.setProperty("jaws.store.client.path", previous);
            }
        }
    }

    /**
     * The stats surface over the same jaws port: a couple of puts and pulls move
     * the reported counters, proving the observability read travels the wire and
     * reflects real traffic without perturbing it.
     */
    @Test
    void statsOverJawsProtocol() throws Exception {
        String previous = System.setProperty("jaws.store.client.path", tempDir.resolve("client-stats").toString());
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.resolve("store-stats").toString());
        config.setHoldScanIntervalMillis(200);

        int port = freePort();
        StoreBroker broker = new StoreBroker(config, port);
        broker.start();
        String directUrl = "127.0.0.1:" + port;
        StoreProducer producer = new StoreProducer(directUrl);
        ReferenceConfig<StoreAdminService> adminRef = adminReference(directUrl);
        StoreAdminService admin = adminRef.getRef();
        try {
            for (int i = 0; i < 3; i++) {
                assertEquals(PutStatus.PUT_OK, producer.sendSync(StoreMessage.of("stats-demo", 0, "x-" + i)).getStatus());
            }
            broker.getMessageStore().getMessageAsync("stats-demo", 0, 0, 32, 0).get(5, TimeUnit.SECONDS);

            StatsResult stats = admin.statsAsync().get(5, TimeUnit.SECONDS);
            assertEquals("broker", stats.getBrokerName());
            assertEquals(3, stats.getPutTotal());
            assertEquals(0, stats.getPutRejectedTotal());
            assertTrue(stats.getPullTotal() >= 1);
            assertTrue(stats.getCommitLogMaxOffset() > 0);
        } finally {
            adminRef.destroy();
            producer.shutdown();
            broker.shutdown();
            if (previous == null) {
                System.clearProperty("jaws.store.client.path");
            } else {
                System.setProperty("jaws.store.client.path", previous);
            }
        }
    }
}
