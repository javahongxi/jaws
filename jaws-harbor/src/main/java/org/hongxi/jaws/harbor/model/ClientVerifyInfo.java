package org.hongxi.jaws.harbor.model;

/**
 * Verify data for a single client, sent during the Distro verify cycle.
 * <p>
 * The receiving peer compares the {@link #revision} with its local copy
 * of the same client.  A mismatch triggers a compensating sync.
 * Matches the Nacos {@code DistroClientVerifyInfo} concept.
 *
 * @author shenhongxi
 */
public class ClientVerifyInfo {

    private String clientId;

    private long revision;

    public ClientVerifyInfo() {
    }

    public ClientVerifyInfo(String clientId, long revision) {
        this.clientId = clientId;
        this.revision = revision;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
