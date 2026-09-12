package org.hongxi.jaws.harbor.distro;

import org.hongxi.jaws.harbor.model.ClientVerifyInfo;

import java.util.List;

/**
 * Transport abstraction for inter-node Distro protocol communication.
 * <p>
 * Implementations send Distro messages (sync, verify, snapshot) to peer
 * Harbor servers using any underlying transport (gRPC, HTTP, etc.).
 *
 * @author shenhongxi
 */
public interface HarborNodeTransport {

    /**
     * Send a sync message to a peer node.
     *
     * @param targetAddress peer address (host:port)
     * @param resourceKey   the clientId (connectionId) of the sync
     * @param operation     the operation type (CHANGE, DELETE)
     * @param content       the serialized {@link org.hongxi.jaws.harbor.model.ClientSyncData}
     * @return true if sync succeeded
     */
    boolean syncData(String targetAddress, String resourceKey, String operation, byte[] content);

    /**
     * Send a verify message to a peer node with per-client revision data.
     *
     * @param targetAddress peer address
     * @param verifyInfos   list of per-client (clientId, revision) for verification
     * @return list of clientIds that are mismatched or missing on the peer;
     *         empty if all matched
     */
    List<String> syncVerify(String targetAddress, List<ClientVerifyInfo> verifyInfos);

    /**
     * Request a full snapshot from a peer node.
     *
     * @param targetAddress peer address
     * @return the snapshot data, or null if failed
     */
    byte[] getSnapshot(String targetAddress);

    /**
     * Shut down the transport and release resources.
     */
    void shutdown();
}
