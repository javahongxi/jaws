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
 *
 * @author shenhongxi
 */
public class ClientSyncData {

    private String clientId;

    /** serviceKeys published by this client. */
    private List<String> serviceKeys;

    /** Instances corresponding 1:1 to {@link #serviceKeys}. */
    private List<Instance> instances;

    /** Revision of the source client at the time of sync. */
    private long revision;

    public ClientSyncData() {
    }

    public ClientSyncData(String clientId, List<String> serviceKeys, List<Instance> instances,
                          long revision) {
        this.clientId = clientId;
        this.serviceKeys = serviceKeys;
        this.instances = instances;
        this.revision = revision;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
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
