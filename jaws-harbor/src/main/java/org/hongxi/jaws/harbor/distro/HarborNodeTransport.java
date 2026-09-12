package org.hongxi.jaws.harbor.distro;

import java.util.Map;

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
     * @param resourceType  the type of resource being synced (e.g. "naming")
     * @param resourceKey   the key of the resource
     * @param operation     the operation type (CHANGE, DELETE)
     * @param content       the serialized data content
     * @return true if sync succeeded
     */
    boolean syncData(String targetAddress, String resourceType, String resourceKey,
                     String operation, byte[] content);

    /**
     * Send a verify message to a peer node.
     *
     * @param targetAddress peer address
     * @param resourceType  resource type
     * @param checksums     map of resourceKey → checksum for verification
     */
    void syncVerify(String targetAddress, String resourceType, Map<String, String> checksums);

    /**
     * Request a full snapshot from a peer node.
     *
     * @param targetAddress peer address
     * @param resourceType  resource type
     * @return the snapshot data, or null if failed
     */
    byte[] getSnapshot(String targetAddress, String resourceType);

    /**
     * Shut down the transport and release resources.
     */
    void shutdown();
}
