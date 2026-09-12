package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.Instance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side session for a single client connection.
 * <p>
 * Tracks which service instances the client has published and which services
 * it has subscribed to.  Mirrors the Nacos {@code AbstractClient} concept:
 * the primary per-connection data store from which the service-level reverse
 * index ({@link ServiceStorage}) is derived.
 * <p>
 * Each client may publish multiple instances per service (matching Nacos's
 * {@code BatchInstancePublishInfo}), so publishers is
 * {@code Map<serviceKey, List<Instance>>}.
 * <p>
 * Each mutation increments the {@link #revision} so that the Distro verify
 * protocol can detect divergence between peers.
 *
 * @author shenhongxi
 */
public class ClientSession {

    private final String clientId;

    /** serviceKey → list of instances published by this client. */
    private final Map<String, List<Instance>> publishers = new ConcurrentHashMap<>();

    /** serviceKeys this client subscribes to. */
    private final Set<String> subscribers = ConcurrentHashMap.newKeySet();

    private final AtomicLong revision = new AtomicLong(0);

    private volatile long lastUpdatedTime;

    /** Whether this client is native (owned by this node) rather than synced from a peer. */
    private volatile boolean nativeClient;

    public ClientSession(String clientId) {
        this.clientId = clientId;
        this.lastUpdatedTime = System.currentTimeMillis();
    }

    public String getClientId() {
        return clientId;
    }

    // ========================================================================
    // Publishers
    // ========================================================================

    /**
     * Add or replace an instance published by this client.
     * If an instance with the same ip#port already exists for the service,
     * it is replaced; otherwise the instance is appended.
     *
     * @param serviceKey "namespace@@group@@serviceName"
     * @param instance   the instance data
     */
    public void addInstance(String serviceKey, Instance instance) {
        publishers.compute(serviceKey, (k, existing) -> {
            if (existing == null) {
                existing = new ArrayList<>();
            }
            String ip = instance.getIp();
            int port = instance.getPort();
            existing.removeIf(inst -> ip.equals(inst.getIp()) && port == inst.getPort());
            existing.add(instance);
            return existing;
        });
        recalculateRevision();
        lastUpdatedTime = System.currentTimeMillis();
    }

    /**
     * Remove a specific instance (by ip#port) for the given service.
     */
    public void removeInstance(String serviceKey, String ip, int port) {
        publishers.computeIfPresent(serviceKey, (k, existing) -> {
            existing.removeIf(inst -> ip.equals(inst.getIp()) && port == inst.getPort());
            return existing.isEmpty() ? null : existing;
        });
        recalculateRevision();
        lastUpdatedTime = System.currentTimeMillis();
    }

    /**
     * Remove all instances for the given service.
     */
    public void removeAllInstances(String serviceKey) {
        List<Instance> removed = publishers.remove(serviceKey);
        if (removed != null) {
            recalculateRevision();
            lastUpdatedTime = System.currentTimeMillis();
        }
    }

    /**
     * Get all instances for the given service.
     */
    public List<Instance> getInstances(String serviceKey) {
        List<Instance> list = publishers.get(serviceKey);
        return list != null ? List.copyOf(list) : List.of();
    }

    public Collection<String> getAllPublishedServices() {
        return publishers.keySet();
    }

    public Map<String, List<Instance>> getAllPublishers() {
        return publishers;
    }

    /**
     * @return total number of instances across all services
     */
    public int getTotalInstanceCount() {
        return publishers.values().stream().mapToInt(List::size).sum();
    }

    // ========================================================================
    // Subscribers
    // ========================================================================

    public void addSubscriber(String serviceKey) {
        subscribers.add(serviceKey);
        lastUpdatedTime = System.currentTimeMillis();
    }

    public void removeSubscriber(String serviceKey) {
        subscribers.remove(serviceKey);
    }

    public Set<String> getAllSubscribedServices() {
        return subscribers;
    }

    // ========================================================================
    // Revision & lifecycle
    // ========================================================================

    /**
     * Recalculate the revision based on current publisher content.
     * Uses a simple hash of service keys and instance ip#port, matching
     * Nacos's approach of detecting data divergence.
     */
    public void recalculateRevision() {
        int hash = 1;
        for (Map.Entry<String, List<Instance>> entry : publishers.entrySet()) {
            for (Instance inst : entry.getValue()) {
                int entryHash = entry.getKey().hashCode() * 31
                        + inst.getIp().hashCode() * 31
                        + inst.getPort();
                hash = hash * 31 + entryHash;
            }
        }
        revision.set(hash);
    }

    public long getRevision() {
        return revision.get();
    }

    public void setRevision(long revision) {
        this.revision.set(revision);
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long time) {
        this.lastUpdatedTime = time;
    }

    public boolean isNativeClient() {
        return nativeClient;
    }

    public void setNativeClient(boolean nativeClient) {
        this.nativeClient = nativeClient;
    }

    /**
     * Release all data — called when the client is being removed.
     */
    public void release() {
        publishers.clear();
        subscribers.clear();
    }

    @Override
    public String toString() {
        return "ClientSession{clientId='" + clientId + "', publishers=" + getTotalInstanceCount()
                + ", subscribers=" + subscribers.size() + ", revision=" + revision.get() + "}";
    }
}
