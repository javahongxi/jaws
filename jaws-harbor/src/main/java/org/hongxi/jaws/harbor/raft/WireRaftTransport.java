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
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.wire.WireClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wire-based {@link RaftTransport}: every Raft RPC rides the same
 * {@code Request.request} unary call on the peer's client port that Distro
 * uses — one port serves clients, AP sync and CP consensus alike.
 * <p>
 * {@link WireClient} calls block, so each one runs on a cached IO pool and
 * completes the returned future; the raft thread itself never waits on the
 * network. Timeouts are short (3s) and retries disabled: Raft supplies its
 * own liveness through heartbeats and repeated elections, so a lost RPC is
 * simply retried by the algorithm, not by the transport.
 *
 * @author shenhongxi
 */
public class WireRaftTransport implements RaftTransport {

    private static final Logger log = LoggerFactory.getLogger(WireRaftTransport.class);

    private static final int REQUEST_TIMEOUT_MS = 3000;
    private static final int CONNECT_TIMEOUT_MS = 2000;

    private final Map<String, WireClient> peerClients = new ConcurrentHashMap<>();
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "harbor-raft-io");
        t.setDaemon(true);
        return t;
    });

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
        ioPool.shutdownNow();
        for (Map.Entry<String, WireClient> entry : peerClients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("[raft] error closing peer client {}: {}", entry.getKey(), e.getMessage());
            }
        }
        peerClients.clear();
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private <T> CompletableFuture<T> sendAsync(String target, Request request, Class<T> responseType) {
        return CompletableFuture.supplyAsync(() -> {
            Payload responsePayload = sendRequest(target, request);
            if (responsePayload == null) {
                throw new IllegalStateException("no response from " + target);
            }
            return HarborProtocol.parseBody(responsePayload, responseType);
        }, ioPool);
    }

    /** Blocking unary call; null on any failure — the future then fails exceptionally. */
    private Payload sendRequest(String target, Request request) {
        String type = HarborProtocol.typeToken(request.getClass());
        try {
            WireClient client = getOrCreateClient(target);
            Payload requestPayload = HarborProtocol.encodeRequest(request);

            DefaultRequest rpcRequest = new DefaultRequest();
            rpcRequest.setInterfaceName(HarborProtocol.RPC_UNARY_SERVICE);
            rpcRequest.setMethodName(HarborProtocol.RPC_UNARY_METHOD);
            rpcRequest.setArguments(new Object[]{requestPayload});

            Response response = client.request(rpcRequest,
                    Payload.getDefaultInstance().getParserForType());
            Object value = response.getValue();
            if (value instanceof Payload payload) {
                return payload;
            }
            log.warn("[raft] unexpected response type from {}: {}",
                    target, value != null ? value.getClass().getName() : "null");
            return null;
        } catch (Exception e) {
            log.warn("[raft] rpc failed: {} -> {}: {}", type, target, e.getMessage());
            return null;
        }
    }

    private WireClient getOrCreateClient(String target) {
        return peerClients.computeIfAbsent(target, addr -> {
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

            WireClient client = new WireClient(url);
            client.open();
            log.info("[raft] peer client opened: {}", addr);
            return client;
        });
    }
}