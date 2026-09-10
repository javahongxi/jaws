package org.hongxi.jaws.harbor.distro;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.harbor.proto.Metadata;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC-based {@link HarborNodeTransport} for multi-node Harbor clusters.
 * <p>
 * Uses {@link WireClient} to send Distro protocol messages (sync, verify,
 * snapshot) to peer Harbor servers over the same gRPC wire format that
 * clients use. Each peer address maps to a lazily-created {@code WireClient}
 * that stays open for the lifetime of the transport.
 * <p>
 * All three Distro operations target the {@code Request.request} unary RPC
 * on the peer, with Payload types {@code DistroSyncRequest},
 * {@code DistroVerifyRequest}, and {@code DistroSnapshotRequest}.
 *
 * @author shenhongxi
 */
public class GrpcHarborNodeTransport implements HarborNodeTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcHarborNodeTransport.class);

    private static final String SERVICE_NAME = "Request";
    private static final String METHOD_NAME = "request";

    private static final String TYPE_DISTRO_SYNC_REQUEST = "DistroSyncRequest";
    private static final String TYPE_DISTRO_VERIFY_REQUEST = "DistroVerifyRequest";
    private static final String TYPE_DISTRO_SNAPSHOT_REQUEST = "DistroSnapshotRequest";

    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;

    private final Map<String, WireClient> peerClients = new ConcurrentHashMap<>();

    @Override
    public boolean syncData(String targetAddress, String resourceType, String resourceKey,
                            String operation, byte[] content) {
        JSONObject body = new JSONObject();
        body.put("resourceType", resourceType);
        body.put("resourceKey", resourceKey);
        body.put("operation", operation);
        body.put("content", content != null
                ? java.util.Base64.getEncoder().encodeToString(content) : "");

        Payload responsePayload = sendRequest(targetAddress, TYPE_DISTRO_SYNC_REQUEST, body);
        if (responsePayload == null) {
            return false;
        }
        JSONObject responseBody = parseBody(responsePayload);
        return responseBody.getIntValue("resultCode") == 200;
    }

    @Override
    public boolean syncVerify(String targetAddress, String resourceType, JSONObject checksums) {
        JSONObject body = new JSONObject();
        body.put("resourceType", resourceType);
        body.put("checksums", checksums);

        Payload responsePayload = sendRequest(targetAddress, TYPE_DISTRO_VERIFY_REQUEST, body);
        if (responsePayload == null) {
            return false;
        }
        JSONObject responseBody = parseBody(responsePayload);
        return responseBody.getIntValue("resultCode") == 200;
    }

    @Override
    public byte[] getSnapshot(String targetAddress, String resourceType) {
        JSONObject body = new JSONObject();
        body.put("resourceType", resourceType);

        Payload responsePayload = sendRequest(targetAddress, TYPE_DISTRO_SNAPSHOT_REQUEST, body);
        if (responsePayload == null) {
            return null;
        }
        JSONObject responseBody = parseBody(responsePayload);
        if (responseBody.getIntValue("resultCode") != 200) {
            log.warn("[harbor] snapshot from {} failed: {}", targetAddress, responseBody);
            return null;
        }
        String base64Content = responseBody.getString("content");
        if (base64Content == null || base64Content.isEmpty()) {
            return new byte[0];
        }
        return java.util.Base64.getDecoder().decode(base64Content);
    }

    @Override
    public void shutdown() {
        for (Map.Entry<String, WireClient> entry : peerClients.entrySet()) {
            try {
                entry.getValue().close();
                log.debug("[harbor] peer client closed: {}", entry.getKey());
            } catch (Exception e) {
                log.warn("[harbor] error closing peer client: {}", entry.getKey(), e);
            }
        }
        peerClients.clear();
        log.info("[harbor] gRPC node transport shut down");
    }

    // ========================================================================
    // Internal
    // ========================================================================

    /**
     * Send a unary Distro request to a peer Harbor server and return the
     * response Payload, or null if the call failed.
     */
    private Payload sendRequest(String targetAddress, String type, JSONObject body) {
        try {
            WireClient client = getOrCreateClient(targetAddress);
            Payload requestPayload = buildPayload(type, body);

            DefaultRequest request = new DefaultRequest();
            request.setInterfaceName(SERVICE_NAME);
            request.setMethodName(METHOD_NAME);
            request.setArguments(new Object[]{requestPayload});

            Response response = client.request(request, Payload.getDefaultInstance().getParserForType());
            Object value = response.getValue();
            if (value instanceof Payload payload) {
                return payload;
            }
            log.warn("[harbor] unexpected response type from {}: {}",
                    targetAddress, value != null ? value.getClass().getName() : "null");
            return null;
        } catch (Exception e) {
            log.warn("[harbor] distro request failed: {} -> {}: {}",
                    type, targetAddress, e.getMessage());
            return null;
        }
    }

    /**
     * Get or lazily create a {@link WireClient} for the given peer address.
     */
    private WireClient getOrCreateClient(String targetAddress) {
        return peerClients.computeIfAbsent(targetAddress, addr -> {
            String[] parts = addr.split(":");
            String host = parts[0].trim();
            int port = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 9848;

            URL url = new URL("wire", host, port, SERVICE_NAME);
            url.addParameter(UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                    String.valueOf(DEFAULT_REQUEST_TIMEOUT_MS));
            url.addParameter(UrlParam.Transport.CONNECT_TIMEOUT.getName(),
                    String.valueOf(DEFAULT_CONNECT_TIMEOUT_MS));
            // Disable retry for inter-node Distro calls — the protocol
            // has its own retry-via-verify mechanism
            url.addParameter(UrlParam.Transport.RETRY_MAX_ATTEMPTS.getName(), "1");

            WireClient client = new WireClient(url);
            client.open();
            log.info("[harbor] peer client opened: {}", addr);
            return client;
        });
    }

    private static Payload buildPayload(String type, JSONObject body) {
        byte[] jsonBytes = JSON.toJSONBytes(body);
        return Payload.newBuilder()
                .setMetadata(Metadata.newBuilder()
                        .setType(type)
                        .build())
                .setBody(Any.newBuilder()
                        .setValue(ByteString.copyFrom(jsonBytes))
                        .build())
                .build();
    }

    private static JSONObject parseBody(Payload payload) {
        byte[] bytes = payload.getBody().getValue().toByteArray();
        if (bytes.length == 0) {
            return new JSONObject();
        }
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }
}
