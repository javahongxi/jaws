package org.hongxi.jaws.harbor.distro;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.request.DistroSnapshotRequest;
import org.hongxi.jaws.harbor.model.request.DistroSyncRequest;
import org.hongxi.jaws.harbor.model.request.DistroVerifyRequest;
import org.hongxi.jaws.harbor.model.response.DistroSnapshotResponse;
import org.hongxi.jaws.harbor.model.response.DistroSyncResponse;
import org.hongxi.jaws.harbor.model.response.DistroVerifyResponse;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gRPC-based {@link HarborNodeTransport} for multi-node Harbor clusters.
 * <p>
 * Uses {@link WireClient}'s native async API to send Distro protocol
 * messages (sync, verify, snapshot) to peer Harbor servers over the same
 * gRPC wire format that clients use — no caller thread ever waits on the
 * network, not even on the first dial to a peer (lazy client creation uses
 * {@code openAsync()}). Each peer address maps to a lazily-created
 * {@code WireClient} that stays open for the lifetime of the transport.
 * <p>
 * All three Distro operations target the {@code Request.request} unary RPC
 * on the peer; {@link HarborProtocol} owns the envelope, so this class names
 * no wire token of its own.
 *
 * @author shenhongxi
 */
public class WireHarborNodeTransport implements HarborNodeTransport {

    private static final Logger log = LoggerFactory.getLogger(WireHarborNodeTransport.class);

    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;

    /** Peer address → client becoming ready; a failed open is evicted so the next call retries. */
    private final Map<String, CompletableFuture<WireClient>> peerClients = new ConcurrentHashMap<>();

    private volatile boolean shutdown;

    @Override
    public CompletableFuture<Boolean> syncData(String targetAddress, String resourceKey,
                                               String operation, byte[] content) {
        DistroSyncRequest request = new DistroSyncRequest();
        request.setResourceKey(resourceKey);
        request.setOperation(operation);
        request.setContent(content != null ? Base64.getEncoder().encodeToString(content) : "");

        return sendRequest(targetAddress, request).thenApply(responsePayload -> {
            if (responsePayload == null) {
                return false;
            }
            DistroSyncResponse response =
                    HarborProtocol.parseBody(responsePayload, DistroSyncResponse.class);
            return response.getResultCode() == 200;
        });
    }

    @Override
    public CompletableFuture<List<String>> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos) {
        DistroVerifyRequest request = new DistroVerifyRequest();
        request.setVerifyInfos(verifyInfos);

        return sendRequest(targetAddress, request).thenApply(responsePayload -> {
            if (responsePayload == null) {
                log.warn("[harbor] verify to {} failed: no response", targetAddress);
                return List.of();
            }
            DistroVerifyResponse response =
                    HarborProtocol.parseBody(responsePayload, DistroVerifyResponse.class);
            if (response.getResultCode() != 200) {
                log.warn("[harbor] verify to {} returned code {}", targetAddress, response.getResultCode());
                List<String> mismatched = response.getMismatchedConnectionIds();
                return mismatched != null ? mismatched : List.of();
            }
            return List.of();
        });
    }

    @Override
    public CompletableFuture<byte[]> getSnapshot(String targetAddress) {
        DistroSnapshotRequest request = new DistroSnapshotRequest();

        return sendRequest(targetAddress, request).thenApply(responsePayload -> {
            if (responsePayload == null) {
                return null;
            }
            DistroSnapshotResponse response =
                    HarborProtocol.parseBody(responsePayload, DistroSnapshotResponse.class);
            if (response.getResultCode() != 200) {
                log.warn("[harbor] snapshot from {} failed: {}", targetAddress, response);
                return null;
            }
            String base64Content = response.getContent();
            if (base64Content == null || base64Content.isEmpty()) {
                return new byte[0];
            }
            return Base64.getDecoder().decode(base64Content);
        });
    }

    @Override
    public void shutdown() {
        shutdown = true;
        for (Map.Entry<String, CompletableFuture<WireClient>> entry : peerClients.entrySet()) {
            // Runs inline for ready clients, on completion for in-flight opens
            entry.getValue().whenComplete((client, e) -> {
                if (client != null) {
                    try {
                        client.close();
                        log.debug("[harbor] peer client closed: {}", entry.getKey());
                    } catch (Exception ex) {
                        log.warn("[harbor] error closing peer client: {}", entry.getKey(), ex);
                    }
                }
            });
        }
        peerClients.clear();
        log.info("[harbor] gRPC node transport shut down");
    }

    // ========================================================================
    // Internal
    // ========================================================================

    /**
     * Send a unary Distro request to a peer Harbor server; the future
     * completes with the response Payload, or null if the call failed.
     */
    private CompletableFuture<Payload> sendRequest(String targetAddress, Request request) {
        String type = HarborProtocol.typeToken(request.getClass());
        return clientFor(targetAddress).thenCompose(client -> {
            Payload requestPayload = HarborProtocol.encodeRequest(request);

            DefaultRequest rpcRequest = new DefaultRequest();
            rpcRequest.setInterfaceName(HarborProtocol.RPC_UNARY_SERVICE);
            rpcRequest.setMethodName(HarborProtocol.RPC_UNARY_METHOD);
            rpcRequest.setArguments(new Object[]{requestPayload});

            return client.request(rpcRequest, Payload.getDefaultInstance().getParserForType())
                    .thenApply(response -> {
                        // The future is already complete here, so getValue()
                        // returns immediately (or rethrows the RPC failure)
                        Object value = response.getValue();
                        if (value instanceof Payload payload) {
                            return payload;
                        }
                        log.warn("[harbor] unexpected response type from {}: {}",
                                targetAddress, value != null ? value.getClass().getName() : "null");
                        return null;
                    });
        }).exceptionally(e -> {
            log.warn("[harbor] distro request failed: {} -> {}: {}",
                    type, targetAddress, e.getMessage());
            return null;
        });
    }

    /**
     * Get or lazily create a {@link WireClient} for the given peer address.
     * The open is fully asynchronous: a failed dial evicts the entry so the
     * next call retries creation, and a shutdown racing the open closes the
     * late-ready client.
     */
    private CompletableFuture<WireClient> clientFor(String targetAddress) {
        AtomicReference<CompletableFuture<WireClient>> created = new AtomicReference<>();
        CompletableFuture<WireClient> ready = peerClients.computeIfAbsent(targetAddress, addr -> {
            WireClient client = new WireClient(buildUrl(addr));
            CompletableFuture<WireClient> f = client.openAsync().thenApply(v -> client);
            created.set(f);
            log.info("[harbor] peer client opening: {}", addr);
            return f;
        });
        if (created.get() != null) {
            // Attach lifecycle cleanup outside computeIfAbsent: the future may
            // already be done and the callback must not modify the map inline
            ready.whenComplete((client, e) -> {
                if (e != null) {
                    peerClients.remove(targetAddress, ready);
                } else if (shutdown) {
                    client.close();
                }
            });
        }
        return ready;
    }

    private URL buildUrl(String addr) {
        String[] parts = addr.split(":");
        String host = parts[0].trim();
        int port = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 19848;

        URL url = new URL("wire", host, port, HarborProtocol.RPC_UNARY_SERVICE);
        url.addParameter(UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                String.valueOf(DEFAULT_REQUEST_TIMEOUT_MS));
        url.addParameter(UrlParam.Transport.CONNECT_TIMEOUT.getName(),
                String.valueOf(DEFAULT_CONNECT_TIMEOUT_MS));
        // Disable retry for inter-node Distro calls — the protocol
        // has its own retry-via-verify mechanism
        url.addParameter(UrlParam.Transport.RETRY_MAX_ATTEMPTS.getName(), "1");
        return url;
    }
}