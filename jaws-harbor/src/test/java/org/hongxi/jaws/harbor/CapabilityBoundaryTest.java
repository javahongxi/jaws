package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.request.InstanceRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a refusal looks like, and that it happens before anything is changed.
 * <p>
 * Harbor's scope is ephemeral-instance naming only. A request for something
 * outside it has to read as a boundary rather than as a broken server — to the
 * caller in the exception text, and to whoever reads the log afterwards.
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
        harborServer = new HarborServer(new URL("harbor", "0.0.0.0", port, ""));
        harborServer.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() {
        harborServer.close();
    }

    @Test
    void persistentInstanceRequestReadsAsABoundaryNotAsAMissingHandler() {
        JSONObject reply = callUnary(new PersistentInstanceRequest());
        assertTrue(reply.getString("message").contains("not supported"),
                "an out-of-scope request must name the boundary: " + reply);
        assertTrue(!reply.getString("message").contains("Unknown request type"),
                "it must not be lumped in with unrecognised tokens: " + reply);
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
    void clientRefusesAPersistentRegistrationBeforeTouchingItsOwnTable() {
        try (HarborClient client = new HarborClient(HarborClientConfig.of("127.0.0.1", port))) {
            Instance persistent = new Instance();
            persistent.setIp("127.0.0.1");
            persistent.setPort(9912);
            persistent.setEphemeral(false);

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> client.registerInstance("refused-locally", persistent));
            assertTrue(thrown.getMessage().contains("ephemeral=false"), thrown.getMessage());

            List<Instance> seen = client.getInstances("refused-locally");
            assertTrue(seen.isEmpty(), "refused locally, so nothing may be owed either");
        }
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
    static class PersistentInstanceRequest extends Request {
    }

    static class NamingFuzzyWatchRequest extends Request {
    }

    static class SomethingNobodyAskedForRequest extends Request {
    }
}
