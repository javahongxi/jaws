package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.request.InstanceRequest;
import org.hongxi.jaws.harbor.model.request.PersistentInstanceRequest;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What a refusal looks like, and that it happens before anything is changed —
 * plus where the old persistent-instance boundary moved to.
 * <p>
 * Persistent instances are now served, but only through their own request type
 * and the CP log: an {@code InstanceRequest} carrying {@code ephemeral=false}
 * is still refused (it would ride the connection-liveness model), while a
 * {@code PersistentInstanceRequest} commits through Raft and outlives the
 * connection that sent it. Everything else outside scope (fuzzy watch, config
 * center) still has to read as a boundary rather than as a broken server.
 * <p>
 * The server-side cases are driven with a raw unary call on purpose: a real
 * nacos-client would never send {@code InstanceRequest} with
 * {@code ephemeral=false} (it switches request types), so that path only exists
 * for a client that got it wrong — including ours.
 *
 * @author shenhongxi
 */
class CapabilityBoundaryTest {

    private static HarborServer harborServer;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        URL url = new URL("harbor", "0.0.0.0", port, "");
        url.addParameter(HarborServer.PARAM_DATA_DIR, HarborTestDataDirs.fresh());
        harborServer = new HarborServer(url);
        harborServer.start();
        awaitListening(port);
        awaitRaftLeader();
    }

    @AfterAll
    static void stopServer() {
        harborServer.close();
    }

    @Test
    void persistentInstanceRequestCommitsThroughTheCpPath() {
        Instance persistent = new Instance();
        persistent.setIp("127.0.0.1");
        persistent.setPort(9911);
        persistent.setEphemeral(false);

        PersistentInstanceRequest request = new PersistentInstanceRequest();
        request.setNamespace("public");
        request.setGroupName("DEFAULT_GROUP");
        request.setServiceName("persistent-via-cp");
        request.setType(HarborProtocol.REGISTER_INSTANCE);
        request.setInstance(persistent);

        JSONObject reply = callUnary(request);
        assertTrue(reply.getBooleanValue("success"), "the CP entrance must serve it: " + reply);
        List<Instance> stored = harborServer.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "persistent-via-cp");
        assertEquals(1, stored.size(), "a committed REGISTER must be readable: " + stored);
        assertFalse(stored.get(0).isEphemeral(), "the stored instance stays persistent");
    }

    @Test
    void fuzzyWatchRequestReadsAsABoundaryToo() {
        JSONObject reply = callUnary(new NamingFuzzyWatchRequest());
        assertTrue(reply.getString("message").contains("NamingFuzzyWatchRequest"),
                "the refusal should name what it refused: " + reply);
    }

    @Test
    void anUnrecognisedTokenStillSaysUnknown() {
        JSONObject reply = callUnary(new SomethingNobodyAskedForRequest());
        assertTrue(reply.getString("message").contains("Unknown request type"), reply.toString());
    }

    @Test
    void instanceRequestCarryingAPersistentInstanceIsRefusedWithoutRegisteringIt() {
        Instance persistent = new Instance();
        persistent.setIp("127.0.0.1");
        persistent.setPort(9911);
        persistent.setEphemeral(false);

        InstanceRequest request = new InstanceRequest();
        request.setNamespace("public");
        request.setGroupName("DEFAULT_GROUP");
        request.setServiceName("persistent-inside-instance-request");
        request.setType(HarborProtocol.REGISTER_INSTANCE);
        request.setInstance(persistent);

        // The refusal carries harbor's shared error shape — a message, and no
        // success flag — the same one every other rejection uses.
        JSONObject reply = callUnary(request);
        assertFalse(reply.getBooleanValue("success"), reply.toString());
        assertTrue(reply.getString("message").contains("ephemeral=false"), reply.toString());

        // Nothing was registered behind the refusal.
        assertTrue(harborServer.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP",
                        "persistent-inside-instance-request").isEmpty(),
                "a refused registration must not have reached storage");
    }

    @Test
    void clientPersistentRegistrationOutlivesTheConnectionThatSentIt() {
        Instance persistent = new Instance();
        persistent.setIp("127.0.0.1");
        persistent.setPort(9912);
        persistent.setEphemeral(false);

        try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port))) {
            client.registerInstance("persistent-outlives-connection", persistent);
            List<Instance> seen = client.getInstances("persistent-outlives-connection");
            assertEquals(1, seen.size(), "the persistent registration must be readable: " + seen);
        }
        // The connection is gone; the CP-owned instance must not be swept with it.
        List<Instance> stored = harborServer.getServiceStorage()
                .getInstances("public", "DEFAULT_GROUP", "persistent-outlives-connection");
        assertEquals(1, stored.size(),
                "a persistent instance belongs to the log, not to the connection: " + stored);
    }

    // ========================================================================
    // Raw call helper
    // ========================================================================

    /**
     * Send one unary request straight over the wire and hand back the JSON body of
     * the reply, so a request type harbor has no DTO for can still be sent. The
     * token comes from the class's simple name like everywhere else, which is why
     * the stubs below carry the Nacos names verbatim.
     */
    private static JSONObject callUnary(Request request) {
        URL url = new URL("wire", "127.0.0.1", port, HarborProtocol.RPC_UNARY_SERVICE);
        WireClient wireClient = new WireClient(url);
        wireClient.open();
        try {
            DefaultRequest rpcRequest = new DefaultRequest();
            rpcRequest.setInterfaceName(HarborProtocol.RPC_UNARY_SERVICE);
            rpcRequest.setMethodName(HarborProtocol.RPC_UNARY_METHOD);
            rpcRequest.setArguments(new Object[]{HarborProtocol.encodeRequest(request)});
            Object value = wireClient.request(rpcRequest,
                    Payload.getDefaultInstance().getParserForType()).getValue();
            Payload payload = (Payload) value;
            return JSON.parseObject(new String(
                    payload.getBody().getValue().toByteArray(), StandardCharsets.UTF_8));
        } finally {
            wireClient.close();
        }
    }

    /** Stands in for the Nacos request of the same name; harbor never parses it. */
    static class NamingFuzzyWatchRequest extends Request {
    }

    static class SomethingNobodyAskedForRequest extends Request {
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

    /** A single-node group elects itself at start; wait for that before proposing. */
    private static void awaitRaftLeader() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (harborServer.getRaftProtocol().raftNode().isLeader()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("the single-node raft group never elected itself");
    }

}
