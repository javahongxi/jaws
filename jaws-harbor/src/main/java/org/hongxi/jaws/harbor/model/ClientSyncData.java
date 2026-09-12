package org.hongxi.jaws.harbor.model;

import java.util.List;

/**
 * Data model for Distro client-level sync.
 * <p>
 * Carries the complete state of a single client connection: all instances
 * it has published and all services it has subscribed to.  This is the
 * unit of Distro CHANGE sync and snapshot loading, matching the Nacos
 * {@code ClientSyncData} concept.
 *
 * @author shenhongxi
 */
public class ClientSyncData {

    private String clientId;

    /** serviceKeys published by this client. */
    private List<String> serviceKeys;

    /** Instances corresponding 1:1 to {@link #serviceKeys}. */
    private List<Instance> instances;

    /** serviceKeys subscribed by this client. */
    private List<String> subscriberKeys;

    /** Revision of the source client at the time of sync. */
    private long revision;

    public ClientSyncData() {
    }

    public ClientSyncData(String clientId, List<String> serviceKeys, List<Instance> instances,
                          List<String> subscriberKeys, long revision) {
        this.clientId = clientId;
        this.serviceKeys = serviceKeys;
        this.instances = instances;
        this.subscriberKeys = subscriberKeys;
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

    public List<String> getSubscriberKeys() {
        return subscriberKeys;
    }

    public void setSubscriberKeys(List<String> subscriberKeys) {
        this.subscriberKeys = subscriberKeys;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }
}
