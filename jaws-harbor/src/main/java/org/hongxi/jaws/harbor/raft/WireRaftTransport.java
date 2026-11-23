package org.hongxi.jaws.harbor.raft;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.request.RaftAppendRequest;
import org.hongxi.jaws.harbor.model.request.RaftInstallSnapshotRequest;
import org.hongxi.jaws.harbor.model.request.RaftVoteRequest;
import org.hongxi.jaws.harbor.model.request.RaftWriteForwardRequest;
import org.hongxi.jaws.harbor.model.response.RaftAppendResponse;
import org.hongxi.jaws.harbor.model.response.RaftInstallSnapshotResponse;
import org.hongxi.jaws.harbor.model.response.RaftVoteResponse;
import org.hongxi.jaws.harbor.model.response.RaftWriteForwardResponse;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wire-based {@link RaftTransport}: every Raft RPC rides the same
 * {@code Request.request} unary call on the peer's client port that Distro
 * uses — one port serves clients, AP sync and CP consensus alike.
 * <p>
 * Calls ride {@link WireClient}'s native async API end to end: the request
 * future is composed directly, with no IO pool bridging a blocking call, so
 * the raft thread never waits on the network — not even on the first dial
 * to a peer, since lazy client creation uses {@code openAsync()}. Timeouts
 * are short (3s) and retries disabled: Raft supplies its own liveness
 * through heartbeats and repeated elections, so a lost RPC is simply
 * retried by the algorithm, not by the transport.
 *
 * @author shenhongxi
 */
public class WireRaftTransport implements RaftTransport {

    private static final Logger log = LoggerFactory.getLogger(WireRaftTransport.class);

    private static final int REQUEST_TIMEOUT_MS = 3000;
    private static final int CONNECT_TIMEOUT_MS = 2000;

    /** Peer address → client becoming ready; a failed open is evicted so the next call retries. */
    private final Map<String, CompletableFuture<WireClient>> peerClients = new ConcurrentHashMap<>();

    private volatile boolean shutdown;

    @Override
    public CompletableFuture<RaftVoteResponse> requestVote(String target, RaftVoteRequest request) {
        return sendAsync(target, request, RaftVoteResponse.class);
    }

    @Override
    public CompletableFuture<RaftAppendResponse> appendEntries(String target, RaftAppendRequest request) {
        return sendAsync(target, request, RaftAppendResponse.class);
    }

    @Override
    public CompletableFuture<RaftInstallSnapshotResponse> installSnapshot(
            String target, RaftInstallSnapshotRequest request) {
        return sendAsync(target, request, RaftInstallSnapshotResponse.class);
    }

    @Override
    public CompletableFuture<RaftWriteForwardResponse> forwardWrite(
            String target, RaftWriteForwardRequest request) {
        return sendAsync(target, request, RaftWriteForwardResponse.class);
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
                    } catch (Exception ex) {
                        log.warn("[raft] error closing peer client {}: {}", entry.getKey(), ex.getMessage());
                    }
                }
            });
        }
        peerClients.clear();
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private <T> CompletableFuture<T> sendAsync(String target, Request request, Class<T> responseType) {
        String type = HarborProtocol.typeToken(request.getClass());
        return clientFor(target).thenCompose(client -> {
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
                        if (value == null) {
                            throw new IllegalStateException("no response from " + target);
                        }
                        if (!(value instanceof Payload payload)) {
                            throw new IllegalStateException("unexpected response type from "
                                    + target + ": " + value.getClass().getName());
                        }
                        return HarborProtocol.parseBody(payload, responseType);
                    });
        }).whenComplete((r, e) -> {
            if (e != null) {
                log.warn("[raft] rpc failed: {} -> {}: {}", type, target, e.getMessage());
            }
        });
    }

    /**
     * Get or lazily create a {@link WireClient} for the given peer address.
     * The open is fully asynchronous: a failed dial evicts the entry so the
     * next RPC retries creation, and a shutdown racing the open closes the
     * late-ready client.
     */
    private CompletableFuture<WireClient> clientFor(String target) {
        AtomicReference<CompletableFuture<WireClient>> created = new AtomicReference<>();
        CompletableFuture<WireClient> ready = peerClients.computeIfAbsent(target, addr -> {
            WireClient client = new WireClient(buildUrl(addr));
            CompletableFuture<WireClient> f = client.openAsync().thenApply(v -> client);
            created.set(f);
            log.info("[raft] peer client opening: {}", addr);
            return f;
        });
        if (created.get() != null) {
            // Attach lifecycle cleanup outside computeIfAbsent: the future may
            // already be done and the callback must not modify the map inline
            ready.whenComplete((client, e) -> {
                if (e != null) {
                    peerClients.remove(target, ready);
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
                String.valueOf(REQUEST_TIMEOUT_MS));
        url.addParameter(UrlParam.Transport.CONNECT_TIMEOUT.getName(),
                String.valueOf(CONNECT_TIMEOUT_MS));
        // Raft retries through its own heartbeat/election rounds.
        url.addParameter(UrlParam.Transport.RETRY_MAX_ATTEMPTS.getName(), "1");
        return url;
    }
}