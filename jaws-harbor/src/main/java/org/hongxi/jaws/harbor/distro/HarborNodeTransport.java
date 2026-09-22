package org.hongxi.jaws.harbor.distro;

import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.request.ConfigBroadcastSyncRequest;

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
     * @param resourceKey   the connectionId of the sync
     * @param operation     the operation type (CHANGE, DELETE)
     * @param content       the serialized {@link org.hongxi.jaws.harbor.model.ClientSyncData}
     * @return true if sync succeeded
     */
    boolean syncData(String targetAddress, String resourceKey, String operation, byte[] content);

    /**
     * Send a verify message to a peer node with per-client revision data.
     *
     * @param targetAddress peer address
     * @param verifyInfos   list of per-client (connectionId, revision) for verification
     * @return list of connectionIds that are mismatched or missing on the peer;
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
     * Relay a dynamic-config broadcast to a peer node, which pushes it to the
     * clients attached to itself and never relays further.
     *
     * @param targetAddress peer address
     * @param request       the broadcast relay (carries the change itself)
     * @return true if the peer acknowledged
     */
    boolean syncConfigBroadcast(String targetAddress, ConfigBroadcastSyncRequest request);

    /**
     * Shut down the transport and release resources.
     */
    void shutdown();
}
