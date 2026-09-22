package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.request.DistroSnapshotRequest;
import org.hongxi.jaws.harbor.model.request.DistroSyncRequest;
import org.hongxi.jaws.harbor.model.request.DistroVerifyRequest;
import org.hongxi.jaws.harbor.model.request.InstanceRequest;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cluster traffic is only accepted from a node this harbor knows.
 * <p>
 * Harbor runs the naming face and the cluster face on one port, so the request
 * type is the only thing telling them apart — a plain nacos-client could send a
 * {@code DistroSyncRequest} and, unchecked, rewrite the service view or pull a
 * full snapshot of it. The guard is therefore an allow-list over the real TCP
 * source address, not over the {@code clientIp} the caller writes into its own
 * metadata, which costs nothing to forge.
 * <p>
 * The negative assertions check the refusal's <em>reason</em>, not just that it
 * failed: an unguarded handler can also answer with a failure (a malformed body
 * throws), and a test that only looks at the flag would pass for the wrong reason.
 *
 * @author shenhongxi
 */
@Timeout(60)
class ClusterGuardSemanticsTest {

    @Test
    void everyClusterEntryPointRefusesAnUnlistedSource() throws Exception {
        // A single node listening on the wildcard address: 0.0.0.0 is where it
        // listens, not who it trusts, so no real TCP source can be a member.
        URL url = new URL("harbor", "0.0.0.0", freePort(), "");
        HarborServer server = new HarborServer(url);
        server.start();
        try {
            awaitListening(url.getPort());
            assertRefusedAsNonMember(url.getPort(), new DistroSnapshotRequest());
            assertRefusedAsNonMember(url.getPort(), new DistroSyncRequest());
            assertRefusedAsNonMember(url.getPort(), new DistroVerifyRequest());
        } finally {
            server.close();
        }
    }

    @Test
    void theSameSourceKeepsFullAccessToTheNamingFace() throws Exception {
        // The guard bites on the cluster face only. A registration arriving from
        // the very address that was just refused for distro traffic is an ordinary
        // SDK client, and refusing it would make the guard a firewall in check form.
        URL url = new URL("harbor", "0.0.0.0", freePort(), "");
        HarborServer server = new HarborServer(url);
        server.start();
        try {
            awaitListening(url.getPort());

            InstanceRequest registration = new InstanceRequest();
            registration.setNamespace("public");
            registration.setGroupName("DEFAULT_GROUP");
            registration.setServiceName("naming-face-still-open");
            registration.setType(HarborProtocol.REGISTER_INSTANCE);
            registration.setInstance(instance("127.0.0.1", 9931));

            JSONObject reply = callUnary(url.getPort(), registration);
            assertTrue(reply.getBooleanValue("success"),
                    "an unlisted source may still register: " + reply);
        } finally {
            server.close();
        }
    }

    @Test
    void aListedMemberIsServedByTheSameClusterCall() throws Exception {
        int port = freePort();
        URL url = new URL("harbor", "127.0.0.1", port, "");
        // The peer is this same node on a nominal port: what the guard matches on is
        // the source address, since a cluster member's listen port is not the
        // ephemeral source port its connection arrives from.
        url.addParameter(HarborServer.PARAM_CLUSTER_MEMBERS, "127.0.0.1:" + (port + 1));
        HarborServer server = new HarborServer(url);
        server.start();
        try {
            awaitListening(port);
            JSONObject reply = callUnary(port, new DistroSnapshotRequest());
            assertTrue(reply.getBooleanValue("success"),
                    "a known member's address must be served: " + reply);
            assertFalse(reply.getString("message") != null
                            && reply.getString("message").contains("cluster member"),
                    "it must not have been refused: " + reply);
        } finally {
            server.close();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void assertRefusedAsNonMember(int listenPort, Request clusterRequest) {
        JSONObject reply = callUnary(listenPort, clusterRequest);
        assertFalse(reply.getBooleanValue("success"),
                "cluster traffic from an unlisted source must be refused: " + reply);
        String message = String.valueOf(reply.getString("message"));
        assertTrue(message.contains("cluster member"),
                "the refusal must name the reason it refused: " + reply);
    }

    /**
     * One unary call straight over the wire, replying with the JSON body — the same
     * shape as {@code CapabilityBoundaryTest}, so a cluster DTO can be sent without
     * a client that would normally send it.
     */
    private static JSONObject callUnary(int listenPort, Request request) {
        URL url = new URL("wire", "127.0.0.1", listenPort, HarborProtocol.RPC_UNARY_SERVICE);
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

    private static Instance instance(String ip, int listenPort) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(listenPort);
        return instance;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Poll the port until it accepts, instead of guessing a startup delay. */
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
