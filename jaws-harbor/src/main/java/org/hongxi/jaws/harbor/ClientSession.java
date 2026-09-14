package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;

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
 * {@code Map<ServiceKey, List<Instance>>}.
 * <p>
 * {@link #revision} is recomputed from the published content on every mutation, so
 * that the Distro verify protocol can detect divergence between peers.
 *
 * @author shenhongxi
 */
public class ClientSession {

    private final String clientId;

    /** service → list of instances published by this client. */
    private final Map<ServiceKey, List<Instance>> publishers = new ConcurrentHashMap<>();

    /** services this client subscribes to. */
    private final Set<ServiceKey> subscribers = ConcurrentHashMap.newKeySet();

    private final AtomicLong revision = new AtomicLong(0);

    /**
     * When the client's OWNING node last vouched for this session — a sync applied
     * or a verify in which the owner's revision matched ours. This is deliberately
     * NOT "when this session was last mutated locally": local bookkeeping on a
     * replica (the expiry tier removing one of its instances, say) would then move
     * the clock, letting every such mutation re-tolerate another full window, and a
     * replica holding many instances could keep itself alive indefinitely.
     * Silence here means silence from the owner.
     * <p>
     * There is no separate "last updated" stamp on this object: it had no reader,
     * and a clock nobody consults only invites being consulted by mistake.
     */
    private volatile long lastOwnerConfirmedTime;

    /**
     * Whether this session belongs to a connection terminating ON THIS NODE
     * ({@code true}) or was replicated from a peer ({@code false}). Counterpart of
     * Nacos {@code ConnectionBasedClient.isNative}, and final for the same reason:
     * identity is decided at birth — {@code ConnectionManager.register} versus
     * {@code ServiceStorage.applyClientSyncData}, mirroring Nacos's
     * {@code newClient}/{@code newSyncedClient} split — and never changes afterwards.
     * A replica that could promote itself to native would silently escape every
     * judgement meant for replicas, including {@link #isReplicaOrphaned}.
     */
    private final boolean nativeClient;

    public ClientSession(String clientId, boolean nativeClient) {
        this.clientId = clientId;
        this.nativeClient = nativeClient;
        // A session born from a sync is confirmed by its owner right now; a native one
        // never consults this clock at all.
        this.lastOwnerConfirmedTime = System.currentTimeMillis();
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
     * @param serviceKey the service identity
     * @param instance   the instance data
     */
    public void addInstance(ServiceKey serviceKey, Instance instance) {
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
    }

    /**
     * Remove a specific instance (by ip#port) for the given service.
     */
    public void removeInstance(ServiceKey serviceKey, String ip, int port) {
        publishers.computeIfPresent(serviceKey, (k, existing) -> {
            existing.removeIf(inst -> ip.equals(inst.getIp()) && port == inst.getPort());
            return existing.isEmpty() ? null : existing;
        });
        recalculateRevision();
    }

    /**
     * Remove all instances for the given service.
     */
    public void removeAllInstances(ServiceKey serviceKey) {
        List<Instance> removed = publishers.remove(serviceKey);
        if (removed != null) {
            recalculateRevision();
        }
    }

    /**
     * Get all instances for the given service.
     */
    public List<Instance> getInstances(ServiceKey serviceKey) {
        List<Instance> list = publishers.get(serviceKey);
        return list != null ? List.copyOf(list) : List.of();
    }

    public Collection<ServiceKey> getAllPublishedServices() {
        return publishers.keySet();
    }

    public Map<ServiceKey, List<Instance>> getAllPublishers() {
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

    public void addSubscriber(ServiceKey serviceKey) {
        subscribers.add(serviceKey);
    }

    public void removeSubscriber(ServiceKey serviceKey) {
        subscribers.remove(serviceKey);
    }

    public Set<ServiceKey> getAllSubscribedServices() {
        return subscribers;
    }

    // ========================================================================
    // Revision & lifecycle
    // ========================================================================

    /**
     * Recalculate the revision based on current publisher content.
     * Uses XOR of per-entry hashes so the result is order-independent —
     * critical because ConcurrentHashMap iteration order depends on
     * physical bucket layout, which can differ between a native session
     * (evolved incrementally) and a synced session (bulk-applied in
     * array order).  Same logical data must always yield the same revision.
     * <p>
     * {@code healthy} participates because it IS replicated content: a verdict the
     * owner flips without any instance change must still be detectable by verify,
     * or a lost health push would leave two nodes disagreeing forever about a live
     * service. {@code lastBeat} deliberately does NOT: it is a wall-clock reading
     * only the owner can take, so folding it in would make native and synced
     * copies disagree by construction and set verify resyncing every cycle.
     */
    public void recalculateRevision() {
        int hash = 0;
        for (Map.Entry<ServiceKey, List<Instance>> entry : publishers.entrySet()) {
            for (Instance inst : entry.getValue()) {
                int entryHash = entry.getKey().hashCode() * 31
                        + inst.getIp().hashCode() * 31
                        + inst.getPort() * 31
                        + (inst.isHealthy() ? 1 : 0);
                hash ^= entryHash;
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

    public long getLastOwnerConfirmedTime() {
        return lastOwnerConfirmedTime;
    }

    /**
     * Record that the owning node vouched for this session now — called when a sync
     * is applied and when the owner's revision matches during verify. Only this clock
     * may rescue a replica from {@code ServiceStorage#reapStaleSyncedClients}.
     */
    public void markOwnerConfirmed() {
        this.lastOwnerConfirmedTime = System.currentTimeMillis();
    }

    public void setLastOwnerConfirmedTime(long time) {
        this.lastOwnerConfirmedTime = time;
    }

    public boolean isNativeClient() {
        return nativeClient;
    }

    /**
     * Whether this REPLICATED session has gone unconfirmed by its owning node for
     * longer than {@code toleranceMs}. Mirrors Nacos
     * {@code ConnectionBasedClient.isExpire(now)} — {@code !isNative() && now -
     * lastRenewTime > clientExpiredTime} — including the ordering that matters:
     * only {@link #markOwnerConfirmed()} can move the deadline, so local
     * bookkeeping on a replica never buys it another window.
     *
     * @param nowMillis   caller-supplied clock, so a sweep judges every session
     *                    against one instant
     * @param toleranceMs how long owner silence is tolerated
     */
    public boolean isReplicaOrphaned(long nowMillis, long toleranceMs) {
        return !nativeClient && nowMillis - lastOwnerConfirmedTime > toleranceMs;
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
