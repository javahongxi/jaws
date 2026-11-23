package org.hongxi.jaws.harbor.distro;

import org.hongxi.jaws.harbor.model.ClientVerifyInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Transport abstraction for inter-node Distro protocol communication.
 * <p>
 * Implementations send Distro messages (sync, verify, snapshot) to peer
 * Harbor servers using any underlying transport (gRPC, HTTP, etc.).
 * <p>
 * Every call is asynchronous so no caller thread waits on the network.
 * RPC failures complete the future with the documented failure value
 * ({@code false} / empty list / {@code null}) rather than exceptionally;
 * only unexpected errors surface as exceptional completion.
 *
 * @author shenhongxi
 */
public interface HarborNodeTransport {

    /**
     * Send a sync message to a peer node.
     *
     * @param targetAddress peer address (host:port)
     * @param resourceKey   the connectionId of the sync
     * @param operation     the operation type (CHANGE, DELETE)
     * @param content       the serialized {@link org.hongxi.jaws.harbor.model.ClientSyncData}
     * @return future completing with true if sync succeeded, false on failure
     */
    CompletableFuture<Boolean> syncData(String targetAddress, String resourceKey, String operation, byte[] content);

    /**
     * Send a verify message to a peer node with per-client revision data.
     *
     * @param targetAddress peer address
     * @param verifyInfos   list of per-client (connectionId, revision) for verification
     * @return future completing with the connectionIds that are mismatched or
     *         missing on the peer; empty if all matched or the call failed
     */
    CompletableFuture<List<String>> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos);

    /**
     * Request a full snapshot from a peer node.
     *
     * @param targetAddress peer address
     * @return future completing with the snapshot data, or null if failed
     */
    CompletableFuture<byte[]> getSnapshot(String targetAddress);

    /**
     * Shut down the transport and release resources.
     */
    void shutdown();
}
