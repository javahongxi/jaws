package org.hongxi.jaws.harbor.model;

import java.util.List;

/**
 * Data model for Distro client-level sync.
 * <p>
 * Carries the <b>published</b> state of a single client connection — this is the
 * unit of Distro CHANGE sync and snapshot loading, matching the Nacos
 * {@code ClientSyncData} concept.
 * <p>
 * Subscriptions are deliberately NOT part of the payload. A subscription belongs
 * to the node holding that connection (Nacos replicates publishers only, see
 * {@code AbstractClient.generateSyncData()}), so a replica has nothing to do with
 * another node's subscriber list: pushes always originate from the node that owns
 * the subscriber's connection. Replicating it would only pollute this node's
 * push-target index with connection ids it cannot write to, and would create
 * replica shells whose removal no DELETE path covers.
 * <p>
 * The class name is Nacos's; the key field is not. Nacos calls it {@code clientId} here
 * and in {@code DistroClientVerifyInfo}, Harbor calls it {@code connectionId}
 * everywhere. That is safe because this payload only ever travels between Harbor nodes
 * — a nacos-client neither sends nor reads it — so both ends rename together. The value
 * identifies one TCP connection, and "client" is exactly the word that let an earlier
 * revision of Harbor fall back to matching by IP.
 *
 * @author shenhongxi
 */
public class ClientSyncData {

    private String connectionId;

    /** serviceKeys published by this client. */
    private List<String> serviceKeys;

    /** Instances corresponding 1:1 to {@link #serviceKeys}. */
    private List<Instance> instances;

    /** Revision of the source client at the time of sync. */
    private long revision;

    public ClientSyncData() {
    }

    public ClientSyncData(String connectionId, List<String> serviceKeys, List<Instance> instances,
                          long revision) {
        this.connectionId = connectionId;
        this.serviceKeys = serviceKeys;
        this.instances = instances;
        this.revision = revision;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public List<String> getServiceKeys() {
        return serviceKeys;
    }

    public void setServiceKeys(List<String> serviceKeys) {
        this.serviceKeys = serviceKeys;
    }

    public List<Instance> getInstances() {
        return instances;
    }

    public void setInstances(List<Instance> instances) {
        this.instances = instances;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
