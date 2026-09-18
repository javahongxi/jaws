package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * In-memory service instance storage for the naming service.
 * <p>
 * Follows the Nacos dual-layer model:
 * <ul>
 *   <li><b>Layer 1 — ClientSession</b> (source of truth): each client connection
 *       owns its published instances and subscriptions.  Instance data lives
 *       exclusively here, in the {@link ClientSession} objects that
 *       {@link ConnectionManager} holds in its {@code clientSessions} map keyed
 *       by {@code connectionId}; this class reaches them via
 *       {@link ConnectionManager#getClientSession} and never stores them.</li>
 *   <li><b>Layer 2 — lightweight indexes</b>: {@code publisherIndexes} and
 *       {@code subscriberIndexes} both map {@link ServiceKey} → Set&lt;connectionId&gt;.
 *       They are ID-only reverse indexes, but they differ in scope: the publisher
 *       index covers clients this node holds <em>and</em> replicas replicated from
 *       peers (any node must be able to answer a routing query), while the subscriber
 *       index holds only connections terminating here — see
 *       {@code doc/harbor-vs-nacos.md} §2.5.</li>
 * </ul>
 * Reading service instances aggregates from ClientSession publishers via the
 * publisher index — matching Nacos's {@code ServiceStorage.getAllInstancesFromIndex()}.
 * <p>
 * Thread-safe: all mutations and reads are safe under concurrent access.
 *
 * @author shenhongxi
 */
public class ServiceStorage {

    private static final Logger log = LoggerFactory.getLogger(ServiceStorage.class);

    /**
     * Publisher reverse index: service → Set&lt;connectionId&gt;.
     * Only stores client IDs, not Instance data — matching Nacos's
     * {@code ClientServiceIndexesManager.publisherIndexes}.
     */
    private final Map<ServiceKey, Set<String>> publisherIndexes = new ConcurrentHashMap<>();

    /**
     * Subscriber reverse index: service → Set&lt;connectionId&gt;. Shard-local by
     * construction: only connections this node holds are ever added (see
     * {@code doc/harbor-vs-nacos.md} §2.5).
     */
    private final Map<ServiceKey, Set<String>> subscriberIndexes = new ConcurrentHashMap<>();

    /**
     * Per-subscriber cluster filter: service -> connectionId -> requested clusters.
     * Nacos keeps this on the {@code Subscriber} object; a push has to be narrowed
     * per subscriber, so the shared payload cannot go out verbatim to everyone.
     */
    private final Map<ServiceKey, Map<String, String>> subscriberClusters = new ConcurrentHashMap<>();

    /**
     * Read cache: service → aggregated {@link ServiceInfo}.
     * Matches Nacos {@code ServiceStorage.serviceDataIndexes}, which is likewise
     * keyed by the service object rather than a joined string.
     * Invalidated on every mutation; populated on read via
     * {@link #buildServiceInfo}.
     */
    private final Map<ServiceKey, ServiceInfo> serviceDataIndexes = new ConcurrentHashMap<>();

    private final ConnectionManager connectionManager;

    private final ServiceChangeListener changeListener;

    /**
     * Called with the connectionId whose HEALTH flipped (unhealthy ↔ healthy), so the
     * owner of that connection can re-publish it. Health is part of the data peers
     * replicate, and only the node holding the connection may judge it — so a
     * verdict has to travel, otherwise every replica keeps a stale answer.
     * Defaults to a no-op for single-node use and tests.
     */
    private final Consumer<String> healthFlipHandler;

    public ServiceStorage(ConnectionManager connectionManager,
                          ServiceChangeListener changeListener) {
        this(connectionManager, changeListener, connectionId -> { });
    }

    public ServiceStorage(ConnectionManager connectionManager,
                          ServiceChangeListener changeListener,
                          Consumer<String> healthFlipHandler) {
        this.connectionManager = connectionManager;
        this.changeListener = changeListener;
        this.healthFlipHandler = healthFlipHandler;
    }

    // ========================================================================
    // Instance registration / deregistration
    // ========================================================================

    /**
     * Register an instance for the given service.
     * <p>
     * Instance data is written to the {@link ClientSession} (source of truth);
     * only the connectionId is added to the publisher index.
     *
     * @param connectionId the gRPC connectionId that registered this instance;
     *                     used to scope deregistration to the owning connection
     *                     so that other connections from the same clientIp are unaffected.
     */
    public void registerInstance(String namespace, String group, String serviceName,
                                 Instance instance, String connectionId) {
        ServiceKey key = ServiceKey.of(namespace, group, serviceName);

        // Write to ClientSession (source of truth)
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session != null) {
            session.addInstance(key, instance);
        }

        // Add connectionId to publisher index
        publisherIndexes.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>())
                .add(connectionId);

        invalidateServiceCache(key);
        log.info("[harbor] instance registered: {} -> {}:{}",
                key, instance.getIp(), instance.getPort());
        changeListener.onServiceChange(key);
    }

    /**
     * Deregister an instance from the given service.
     */
    public void deregisterInstance(String namespace, String group, String serviceName,
                                   Instance instance, String connectionId) {
        ServiceKey key = ServiceKey.of(namespace, group, serviceName);
        String ip = instance.getIp();
        int port = instance.getPort();

        // Locate the ClientSession directly via connectionId
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session != null) {
            for (Instance cached : session.getInstances(key)) {
                if (ip.equals(cached.getIp()) && port == cached.getPort()) {
                    session.removeInstance(key, ip, port);
                    invalidateServiceCache(key);
                    // Update the publisher index only once this client's last
                    // instance of the service is gone: the index is per client while
                    // instances are per instance, and dropping the client here would
                    // make an over-count in hasPublishers — retireIfEmpty then retires
                    // a service the client is still publishing.
                    if (session.getInstances(key).isEmpty()) {
                        Set<String> connectionIds = publisherIndexes.get(key);
                        if (connectionIds != null) {
                            connectionIds.remove(connectionId);
                            if (connectionIds.isEmpty()) {
                                publisherIndexes.remove(key);
                            }
                        }
                    }
                    log.info("[harbor] instance deregistered: {} -> {}:{}", key, ip, port);
                    announceChange(key);
                    retireIfEmpty(key);
                    return;
                }
            }
        }
        // No match is normal under idempotent retry (the client's redo loop re-sends
        // DE_REGISTER, or a deregister lands after the connection was already cleaned
        // up) — not an anomaly, so keep it at debug to avoid noise.
        log.debug("[harbor] instance deregister (no match): {} -> {}:{}", key, ip, port);
    }

    // ========================================================================
    // Instance query — aggregated from ClientSession (Nacos-style)
    // ========================================================================

    /**
     * Query all instances for the given service.
     * <p>
     * Aggregates from each ClientSession that registered for this service,
     * matching Nacos's {@code ServiceStorage.getAllInstancesFromIndex()}.
     */
    public List<Instance> getInstances(String namespace, String group, String serviceName) {
        return aggregateInstances(ServiceKey.of(namespace, group, serviceName));
    }

    /**
     * Aggregate instances for a service from all ClientSession publishers.
     */
    private List<Instance> aggregateInstances(ServiceKey serviceKey) {
        Set<String> connectionIds = publisherIndexes.get(serviceKey);
        if (connectionIds == null || connectionIds.isEmpty()) {
            return List.of();
        }
        List<Instance> result = new ArrayList<>();
        for (String connectionId : connectionIds) {
            ClientSession session = connectionManager.getClientSession(connectionId);
            if (session != null) {
                result.addAll(session.getInstances(serviceKey));
            }
        }
        return result;
    }

    // ========================================================================
    // Subscriber management
    // ========================================================================

    /**
     * Add a subscriber for the given service, watching every cluster.
     */
    public void addSubscriber(String namespace, String group, String serviceName,
                              String connectionId) {
        addSubscriber(namespace, group, serviceName, connectionId, "");
    }

    /**
     * Add a subscriber for the given service with its cluster filter.
     *
     * @param clusters comma-separated cluster allow-list, empty for all clusters
     */
    public void addSubscriber(String namespace, String group, String serviceName,
                              String connectionId, String clusters) {
        ServiceKey key = ServiceKey.of(namespace, group, serviceName);
        subscriberIndexes.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>())
                .add(connectionId);
        subscriberClusters.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .put(connectionId, clusters == null ? "" : clusters);
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session != null) {
            session.addSubscriber(key);
        }
        log.debug("[harbor] subscriber added: conn={} for {}", connectionId, key);
    }

    /**
     * Remove a subscriber from the given service.
     */
    public void removeSubscriber(String namespace, String group, String serviceName,
                                 String connectionId) {
        ServiceKey key = ServiceKey.of(namespace, group, serviceName);
        Set<String> subscribers = subscriberIndexes.get(key);
        if (subscribers != null) {
            subscribers.remove(connectionId);
            if (subscribers.isEmpty()) {
                subscriberIndexes.remove(key);
            }
        }
        Map<String, String> clustersOfKey = subscriberClusters.get(key);
        if (clustersOfKey != null) {
            clustersOfKey.remove(connectionId);
            if (clustersOfKey.isEmpty()) {
                subscriberClusters.remove(key);
            }
        }
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session != null) {
            session.removeSubscriber(key);
        }
        // Unsubscribe only: nothing routable changed for the subscribers that stay.
        retireIfEmpty(key);
    }

    /**
     * Remove all subscriber entries for a given connection (on disconnect).
     */
    public void removeAllSubscribersForConnection(String connectionId) {
        Set<ServiceKey> affectedServices = new HashSet<>();
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session != null) {
            for (ServiceKey serviceKey : session.getAllSubscribedServices()) {
                Set<String> subscribers = subscriberIndexes.get(serviceKey);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                    if (subscribers.isEmpty()) {
                        subscriberIndexes.remove(serviceKey);
                    }
                }
                removeSubscriberFilter(serviceKey, connectionId);
                affectedServices.add(serviceKey);
            }
        } else {
            // Fallback: scan all subscriber entries
            for (Map.Entry<ServiceKey, Set<String>> entry : subscriberIndexes.entrySet()) {
                entry.getValue().remove(connectionId);
                if (entry.getValue().isEmpty()) {
                    subscriberIndexes.remove(entry.getKey());
                }
                removeSubscriberFilter(entry.getKey(), connectionId);
                affectedServices.add(entry.getKey());
            }
        }
        // Check affected services for emptiness — no instance was removed, so no re-push
        for (ServiceKey serviceKey : affectedServices) {
            // unsubscribe sweep: nothing routable changed
            retireIfEmpty(serviceKey);
        }
    }

    // ========================================================================
    // Service listing & info
    // ========================================================================

    /**
     * List all registered service names in a tenant/group pair.
     */
    public List<String> listServices(String namespace, String group) {
        List<String> result = new ArrayList<>();
        for (ServiceKey key : publisherIndexes.keySet()) {
            if (key.namespace().equals(namespace) && key.group().equals(group)) {
                result.add(key.name());
            }
        }
        return result;
    }

    /**
     * Build a {@link ServiceInfo} for the given service, including all instances.
     */
    public ServiceInfo buildServiceInfo(String namespace, String group, String serviceName) {
        ServiceKey key = ServiceKey.of(namespace, group, serviceName);
        ServiceInfo cached = serviceDataIndexes.get(key);
        if (cached != null) {
            return cached;
        }

        List<Instance> instances = aggregateInstances(key);
        String groupedName = key.groupedName();

        ServiceInfo info = new ServiceInfo();
        info.setName(groupedName);
        info.setGroupName(group);
        info.setClusters("");
        info.setCacheMillis(10000);
        info.setLastRefTime(System.currentTimeMillis());
        info.setChecksum("");
        info.setAllIPs(false);
        info.setReachProtectionThreshold(false);

        List<Instance> hosts = new ArrayList<>();
        for (Instance inst : instances) {
            Instance host = new Instance();
            host.setIp(inst.getIp());
            host.setPort(inst.getPort());
            host.setWeight(inst.getWeight());
            host.setHealthy(inst.isHealthy());
            host.setEnabled(inst.isEnabled());
            host.setEphemeral(inst.isEphemeral());
            host.setClusterName(inst.getClusterName());
            host.setServiceName(groupedName);
            host.setInstanceId(inst.getInstanceId());
            host.setMetadata(inst.getMetadata() != null ? inst.getMetadata() : new HashMap<>());
            hosts.add(host);
        }
        info.setHosts(hosts);
        serviceDataIndexes.put(key, info);
        return info;
    }

    /**
     * Read-only view of the connections currently subscribed to a service. The push
     * engine re-reads this at fire time so it always targets the live subscriber set,
     * not a stale one — and only ever this node's connections (shard-local, see
     * {@code doc/harbor-vs-nacos.md} §2.5).
     */
    public Set<String> getSubscriberConnections(ServiceKey serviceKey) {
        Set<String> subscribers = subscriberIndexes.get(serviceKey);
        return subscribers == null ? Set.of() : Set.copyOf(subscribers);
    }

    /**
     * The cluster filter a subscriber asked for, {@code ""} when it watches every
     * cluster. Re-read with the live subscriber set so a push narrows per watcher.
     */
    public String getSubscriberClusters(ServiceKey serviceKey, String connectionId) {
        Map<String, String> filters = subscriberClusters.get(serviceKey);
        String clusters = filters == null ? null : filters.get(connectionId);
        return clusters == null ? "" : clusters;
    }

    private void removeSubscriberFilter(ServiceKey serviceKey, String connectionId) {
        Map<String, String> filters = subscriberClusters.get(serviceKey);
        if (filters == null) {
            return;
        }
        filters.remove(connectionId);
        if (filters.isEmpty()) {
            subscriberClusters.remove(serviceKey);
        }
    }

    // ========================================================================
    // Heartbeat health check
    // ========================================================================

    /**
     * Reconcile ephemeral instance health against connection liveness — the
     * Nacos 2.x model where health is a property of the client connection, not a
     * per-instance beat. Only connections this node holds are considered: a native
     * connection has a {@link ConnectionManager.ConnectionRecord} (replicas do not,
     * so they are never judged here — they learn the verdict as replicated data).
     * A connection idle past {@code timeoutMs} flips its instances
     * {@code healthy=false}; activity flips them back to {@code healthy=true}. Every
     * flip invalidates the service cache, folds into the session revision (so a lost
     * push is caught by verify), and notifies — so an outage steers traffic away and
     * a recovered provider starts receiving it again.
     *
     * @param timeoutMs idle window after which a connection's instances go unhealthy
     */
    public void reconcileHealth(long timeoutMs) {
        long now = System.currentTimeMillis();
        Set<ServiceKey> affectedServices = new HashSet<>();
        Set<String> flippedConnections = new HashSet<>();
        for (ConnectionManager.ConnectionRecord record : connectionManager.allConnections()) {
            ClientSession session = connectionManager.getClientSession(record.connectionId());
            if (session == null) {
                continue;
            }
            boolean wantHealthy = !record.isStale(now, timeoutMs);
            boolean flipped = false;
            for (Map.Entry<ServiceKey, List<Instance>> entry : session.getAllPublishers().entrySet()) {
                for (Instance inst : entry.getValue()) {
                    if (inst.isHealthy() != wantHealthy) {
                        inst.setHealthy(wantHealthy);
                        invalidateServiceCache(entry.getKey());
                        affectedServices.add(entry.getKey());
                        flipped = true;
                    }
                }
            }
            if (flipped) {
                // Health is part of the replicated content: without folding it into
                // the revision, a lost push for a client whose instances did not
                // otherwise change would never be noticed by verify.
                session.recalculateRevision();
                flippedConnections.add(session.getConnectionId());
            }
        }
        for (ServiceKey serviceKey : affectedServices) {
            changeListener.onServiceChange(serviceKey);
        }
        for (String connectionId : flippedConnections) {
            healthFlipHandler.accept(connectionId);
        }
    }

    /**
     * Remove all instances registered by the given connection across all services.
     * Called when a client connection is closed (bi-stream completed/error or
     * channelInactive). Only removes instances owned by this connection, leaving
     * instances from other connections (even with the same clientIp) untouched.
     * Notifies subscribers for each affected service.
     *
     * @param connectionId the connectionId from ServerCheck
     * @return total number of instances removed
     */
    public int deregisterInstancesByConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) {
            return 0;
        }
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session == null || session.getAllPublishers().isEmpty()) {
            return 0;
        }
        int totalRemoved = 0;
        for (Map.Entry<ServiceKey, List<Instance>> entry : session.getAllPublishers().entrySet()) {
            ServiceKey serviceKey = entry.getKey();
            int count = entry.getValue().size();
            totalRemoved += count;
            session.removeAllInstances(serviceKey);
            // Remove connectionId from publisher index
            Set<String> connectionIds = publisherIndexes.get(serviceKey);
            if (connectionIds != null) {
                connectionIds.remove(connectionId);
                if (connectionIds.isEmpty()) {
                    publisherIndexes.remove(serviceKey);
                }
            }
            invalidateServiceCache(serviceKey);
            log.info("[harbor] instance(s) deregistered on disconnect: {} -> connId={} ({} instance(s))",
                    serviceKey, connectionId, count);
            announceChange(serviceKey);
            retireIfEmpty(serviceKey);
        }
        return totalRemoved;
    }

    // ========================================================================
    // Distro protocol support
    // ========================================================================

    /**
     * Get all instance data aggregated from ClientSessions, grouped by service key.
     * Used by the HTTP management API, which speaks the joined string form.
     */
    public Map<String, List<Instance>> getAllInstanceData() {
        Map<String, List<Instance>> result = new HashMap<>();
        for (ServiceKey serviceKey : publisherIndexes.keySet()) {
            List<Instance> instances = aggregateInstances(serviceKey);
            if (!instances.isEmpty()) {
                result.put(serviceKey.toKeyString(), instances);
            }
        }
        return result;
    }

    /**
     * Build a {@link ClientSyncData} from the given connection's ClientSession.
     * Used by Distro CHANGE sync to send the client's full <em>published</em>
     * state to peers; subscriptions stay local (see {@link ClientSyncData}).
     *
     * @param connectionId the connection whose data to export
     * @return the sync data, or {@code null} if the client session is not found
     */
    public ClientSyncData buildClientSyncData(String connectionId) {
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session == null) {
            return null;
        }
        List<String> serviceKeys = new ArrayList<>();
        List<Instance> instances = new ArrayList<>();
        for (Map.Entry<ServiceKey, List<Instance>> entry : session.getAllPublishers().entrySet()) {
            for (Instance inst : entry.getValue()) {
                serviceKeys.add(entry.getKey().toKeyString());
                instances.add(inst);
            }
        }
        return new ClientSyncData(
                connectionId,
                serviceKeys,
                instances,
                session.getRevision()
        );
    }

    /**
     * Apply a {@link ClientSyncData} received from a peer via Distro sync.
     * Creates or replaces a local ClientSession (marked as non-native) and
     * updates the publisher index with the connectionId.
     * <p>
     * Uses full-replacement semantics: the old client data is removed and
     * replaced with the incoming, matching Nacos's
     * {@code upgradeClient()} behaviour.
     */
    public void applyClientSyncData(ClientSyncData data) {
        if (data == null || data.getConnectionId() == null) {
            return;
        }
        String connectionId = data.getConnectionId();

        // Skip if this client is a native client (the node's own connection).
        // Native clients are authoritative locally and must never be overwritten
        // by a peer's potentially stale copy.
        ClientSession existing = connectionManager.getClientSession(connectionId);
        if (existing != null && existing.isNativeClient()) {
            log.debug("[harbor] skipping client sync for native client: {}", connectionId);
            return;
        }

        // Whether this apply changes what local subscribers route by. The owner folds
        // content into {@code revision} (XOR, order-independent — see
        // ClientSession#recalculateRevision), so an identical revision means the peer
        // re-pushed the exact same state (a coalesced echo or a verify re-sync of an
        // unchanged client): nothing a subscriber would notice has changed, so no push —
        // the same dataChanged gate the delete path applies. A first-seen replica is
        // always a change (its instances become newly routable on this node).
        boolean routableChanged = existing == null || existing.getRevision() != data.getRevision();
        Set<ServiceKey> affected = new HashSet<>();

        // A re-sync replaces the previous copy: drop this client from exactly the
        // services the old replica held (existing is non-null and, per the check
        // above, non-native). First-time syncs have nothing to undo.
        if (existing != null) {
            for (ServiceKey previous : existing.getAllPublishedServices()) {
                affected.add(previous);
                removeFromPublisherIndex(previous, connectionId);
                invalidateServiceCache(previous);
            }
        }

        // Create or update the ClientSession
        ClientSession session = new ClientSession(connectionId, false);

        // Apply publishers — write to ClientSession + add connectionId to publisherIndexes
        List<String> serviceKeys = data.getServiceKeys();
        List<Instance> instances = data.getInstances();
        if (serviceKeys != null && instances != null) {
            for (int i = 0; i < serviceKeys.size() && i < instances.size(); i++) {
                // Wire form is the joined string; parse it once here. A malformed key
                // throws, which onSync reports as a failed apply rather than indexing
                // data under a wrong service.
                ServiceKey serviceKey = ServiceKey.parse(serviceKeys.get(i));
                Instance instance = instances.get(i);
                session.addInstance(serviceKey, instance);
                publisherIndexes.computeIfAbsent(serviceKey, k -> new CopyOnWriteArraySet<>())
                        .add(connectionId);
                invalidateServiceCache(serviceKey);
                affected.add(serviceKey);
            }
        }

        // Set the authoritative revision AFTER all addInstance calls,
        // because each addInstance triggers recalculateRevision() which
        // would otherwise overwrite the source revision we just received.
        session.setRevision(data.getRevision());

        // Applying a peer's full state renews this replica's confirmation clock.
        session.onRenew();
        connectionManager.putClientSession(connectionId, session);
        log.info("[harbor] applied client sync: {} (publishers={})",
                connectionId, session.getTotalInstanceCount());

        // Close the delete-vs-change asymmetry: a replicated CHANGE must notify this
        // node's local subscribers exactly like a replicated DELETE does (removeSyncedClient
        // -> retireIfEmpty -> onServiceChange), so a subscriber on a non-owner
        // node learns of the change by push, not only on its next poll. Notify the union of
        // services this client left/entered, gated on content actually having moved.
        if (routableChanged) {
            for (ServiceKey serviceKey : affected) {
                changeListener.onServiceChange(serviceKey);
            }
        }
    }

    /**
     * Reap replicated (non-native) client sessions that no peer has confirmed for a
     * full expiry window, and return how many were reclaimed.
     * <p>
     * When the node owning a client's connection dies, that client can never be
     * announced again: no beat reaches this node, no Distro DELETE arrives, and the
     * connection watchdog cannot see the replica at all (a synced session has no
     * {@code ConnectionRecord}, and liveness rides on that record's own clock).
     * Without this pass the {@link ClientSession} shell
     * and its reverse-index entries survive for the process lifetime — a subscriber-only
     * replica is invisible to every beat-based tier, so it leaks outright.
     * <p>
     * The predicate is {@link ClientSession#getLastRenewTime()}, advanced only
     * when the owner vouches for the session (a sync applied, or a verify in which its
     * revision matched ours) and NEVER by local bookkeeping such as the expiry tier
     * dropping one of the replica instances. So an owner that keeps confirming holds its
     * replicas, and one that goes silent loses them exactly one window after the
     * silence - not one window after the last thing this node did to the copy.
     * Native sessions are excluded by {@link ClientSession#isReplicaOrphaned}: they are
     * authoritative locally and their clients are judged by the beat tiers, so an idle
     * local connection is never a dead one here.
     *
     * @param timeoutMs how long an unconfirmed replica is tolerated
     * @return the number of reclaimed sessions
     */
    public int reapStaleSyncedClients(long timeoutMs) {
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (ClientSession session : connectionManager.allClientSessions()) {
            // The predicate lives on the session (Nacos puts isExpire on the client for
            // the same reason): whether a replica may be dropped is a fact about its own
            // identity and confirmation clock, not about this sweep.
            if (session.isReplicaOrphaned(now, timeoutMs)) {
                stale.add(session.getConnectionId());
            }
        }
        for (String connectionId : stale) {
            log.info("[harbor] reaping replica of client unseen for {}ms: {}", timeoutMs, connectionId);
            removeSyncedClient(connectionId);
        }
        return stale.size();
    }

    /**
     * Remove a synced (non-native) client and all its index entries.
     * Called when a Distro DELETE is received for a connection.
     */
    public void removeSyncedClient(String connectionId) {
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session == null) {
            return;
        }
        // Snapshot before release(): afterwards the session no longer knows what it held.
        Set<ServiceKey> affectedServices = new HashSet<>(session.getAllPublishedServices());
        session.release();
        connectionManager.removeClientSession(connectionId);
        // Per-service cleanup of the publisher index, then the emptiness check that
        // also retires the service itself: the caller knows exactly which services
        // this client published, so no index-wide scan is needed.
        for (ServiceKey serviceKey : affectedServices) {
            removeFromPublisherIndex(serviceKey, connectionId);
            // dropping a synced client removes its instances
            announceChange(serviceKey);
            retireIfEmpty(serviceKey);
        }
        log.info("[harbor] removed synced client: {}", connectionId);
    }

    /**
     * Remove one client from the publisher index of one service, dropping the entry
     * when no publisher is left.
     * <p>
     * There is deliberately no counterpart for {@code subscriberIndexes}: a
     * subscription is connection-local, so a client replicated from a peer never
     * enters that index, and the connections that do are cleared by
     * {@link #removeAllSubscribersForConnection(String)} when the connection closes.
     */
    private void removeFromPublisherIndex(ServiceKey serviceKey, String connectionId) {
        Set<String> connectionIds = publisherIndexes.get(serviceKey);
        if (connectionIds == null) {
            return;
        }
        connectionIds.remove(connectionId);
        if (connectionIds.isEmpty()) {
            publisherIndexes.remove(serviceKey);
        }
    }

    /**
     * Retire a service that has neither publishers nor subscribers by dropping every
     * index and cache entry. This is the only place an emptied service is reclaimed, so it
     * runs on every removal path — instance change or not.
     * <p>
     * It announces nothing: a surviving subscriber already learned the service is empty at
     * the moment its last instance was removed (see {@link #announceChange}), so by the time
     * both sets are empty no one is left to notify — the deferred push would re-read an
     * empty subscriber set at fire time and deliver to zero connections.
     */
    private void retireIfEmpty(ServiceKey serviceKey) {
        if (hasPublishers(serviceKey) || hasSubscribers(serviceKey)) {
            return;
        }
        publisherIndexes.remove(serviceKey);
        subscriberIndexes.remove(serviceKey);
        subscriberClusters.remove(serviceKey);
        invalidateServiceCache(serviceKey);
        log.info("[harbor] empty service cleaned: {}", serviceKey);
    }

    /**
     * Announce a surviving service's new instance set to its subscribers — including the
     * empty set the moment its last instance is removed while watchers still remain. Call
     * this only from a path that actually changed what is routable (register/deregister/
     * expire/connection-closed/synced-client-gone): the periodic sweep and a bare
     * unsubscribe do NOT call it, because Nacos pushes on change and re-pushing every idle
     * service on every 5 s sweep is O(services) redundant traffic (locked out by
     * NotifyOnChangeOnlyTest). A service that is already fully empty (no publishers AND no
     * subscribers) is left to {@link #retireIfEmpty}, which retires it silently.
     */
    private void announceChange(ServiceKey serviceKey) {
        if (!hasPublishers(serviceKey) && !hasSubscribers(serviceKey)) {
            return;
        }
        changeListener.onServiceChange(serviceKey);
    }

    private boolean hasPublishers(ServiceKey serviceKey) {
        Set<String> publishers = publisherIndexes.get(serviceKey);
        return publishers != null && !publishers.isEmpty();
    }

    private boolean hasSubscribers(ServiceKey serviceKey) {
        Set<String> subscribers = subscriberIndexes.get(serviceKey);
        return subscribers != null && !subscribers.isEmpty();
    }

    /**
     * Periodically clean up empty services (no publishers and no subscribers).
     * Called by {@link HealthCheckScheduler} after instance/connection cleanup.
     */
    public void cleanEmptyServices() {
        Set<ServiceKey> allKeys = new HashSet<>();
        allKeys.addAll(publisherIndexes.keySet());
        allKeys.addAll(subscriberIndexes.keySet());
        for (ServiceKey serviceKey : allKeys) {
            // idle sweep must not re-push every service every cycle
            retireIfEmpty(serviceKey);
        }
    }

    private void invalidateServiceCache(ServiceKey serviceKey) {
        serviceDataIndexes.remove(serviceKey);
    }

    /**
     * Callback for notifying subscribers of service changes.
     */
    @FunctionalInterface
    public interface ServiceChangeListener {
        void onServiceChange(ServiceKey service);
    }
}
