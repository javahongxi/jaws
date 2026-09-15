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
 *       exclusively here.</li>
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

        // Set heartbeat timestamps
        long currentTime = System.currentTimeMillis();
        instance.setRegisterTime(currentTime);
        instance.setLastBeat(currentTime);
        instance.setConnectionId(connectionId);

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
                    // Update publisher index
                    Set<String> connectionIds = publisherIndexes.get(key);
                    if (connectionIds != null) {
                        connectionIds.remove(connectionId);
                        if (connectionIds.isEmpty()) {
                            publisherIndexes.remove(key);
                        }
                    }
                    log.info("[harbor] instance deregistered: {} -> {}:{}", key, ip, port);
                    checkAndCleanEmptyService(key, true);
                    return;
                }
            }
        }
        log.warn("[harbor] instance deregister (no match): {} -> {}:{}", key, ip, port);
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
     * Add a subscriber for the given service.
     */
    public void addSubscriber(String namespace, String group, String serviceName,
                              String connectionId) {
        ServiceKey key = ServiceKey.of(namespace, group, serviceName);
        subscriberIndexes.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>())
                .add(connectionId);
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
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session != null) {
            session.removeSubscriber(key);
        }
        // Unsubscribe only: nothing routable changed for the subscribers that stay.
        checkAndCleanEmptyService(key, false);
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
                affectedServices.add(serviceKey);
            }
        } else {
            // Fallback: scan all subscriber entries
            for (Map.Entry<ServiceKey, Set<String>> entry : subscriberIndexes.entrySet()) {
                entry.getValue().remove(connectionId);
                if (entry.getValue().isEmpty()) {
                    subscriberIndexes.remove(entry.getKey());
                }
                affectedServices.add(entry.getKey());
            }
        }
        // Check affected services for emptiness — no instance was removed, so no re-push
        for (ServiceKey serviceKey : affectedServices) {
            // unsubscribe sweep: nothing routable changed
            checkAndCleanEmptyService(serviceKey, false);
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

    // ========================================================================
    // Heartbeat health check
    // ========================================================================

    /**
     * Update the last heartbeat for all instances registered by the given connection.
     * Follows Nacos's connection-based health check model: any request from a
     * connection refreshes the heartbeat for all its registered instances.
     *
     * @param connectionId the gRPC connectionId from the wire call context
     */
    public void updateHeartbeatByConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) {
            return;
        }
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Set<ServiceKey> recoveredServices = new HashSet<>();
        boolean flipped = false;
        for (Map.Entry<ServiceKey, List<Instance>> entry : session.getAllPublishers().entrySet()) {
            for (Instance inst : entry.getValue()) {
                inst.setLastBeat(now);
                // Beat recovered: clear a prior unhealthy mark and re-announce,
                // so subscribers stop steering traffic away from it.
                if (!inst.isHealthy()) {
                    inst.setHealthy(true);
                    invalidateServiceCache(entry.getKey());
                    recoveredServices.add(entry.getKey());
                    flipped = true;
                }
            }
        }
        for (ServiceKey serviceKey : recoveredServices) {
            changeListener.onServiceChange(serviceKey);
        }
        if (flipped) {
            // Recovery must travel as hard as the outage: a replica left unhealthy
            // keeps steering traffic away from a provider that is alive again.
            session.recalculateRevision();
            healthFlipHandler.accept(connectionId);
        }
    }

    /**
     * First health tier (Nacos {@code HEART_BEAT_TIMEOUT}): an instance whose beat
     * stopped for longer than {@code timeoutMs} — but which hasn't yet hit the delete
     * window — is marked {@code healthy=false}, its service cache invalidated, and
     * subscribers notified so they stop routing to it. Deletion stays a later tier
     * via {@link #getExpiredInstances}; the next beat restores health via
     * {@link #updateHeartbeatByConnectionId}.
     */
    public void markUnhealthyStale(long timeoutMs) {
        long now = System.currentTimeMillis();
        Set<ServiceKey> affectedServices = new HashSet<>();
        Set<String> flippedConnections = new HashSet<>();
        for (ClientSession session : connectionManager.allClientSessions()) {
            // Only the node HOLDING the connection may judge health. A replica's
            // copy of lastBeat is frozen at push time — beats are not forwarded per
            // beat — so evaluating it locally invents an outage the owner never saw
            // and pushes a notification that steers traffic away from a live
            // provider. A replica learns the verdict as data instead.
            if (!session.isNativeClient()) {
                continue;
            }
            boolean flipped = false;
            for (Map.Entry<ServiceKey, List<Instance>> entry : session.getAllPublishers().entrySet()) {
                for (Instance inst : entry.getValue()) {
                    long lastBeat = inst.getLastBeat();
                    if (inst.isHealthy() && lastBeat > 0 && now - lastBeat > timeoutMs) {
                        inst.setHealthy(false);
                        invalidateServiceCache(entry.getKey());
                        affectedServices.add(entry.getKey());
                        flipped = true;
                    }
                }
            }
            if (flipped) {
                // Health is part of the replicated content: without folding it into
                // the revision, a lost push for a client whose instances did not
                // change would never be noticed by verify.
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
     * Find all ephemeral instances whose last heartbeat exceeds the timeout.
     *
     * @param timeoutMs the heartbeat timeout in milliseconds
     * @return list of expired instance descriptors (service + ip + port)
     */
    public List<ExpiredInstance> getExpiredInstances(long timeoutMs) {
        long now = System.currentTimeMillis();
        List<ExpiredInstance> expired = new ArrayList<>();
        for (ClientSession session : connectionManager.allClientSessions()) {
            for (Map.Entry<ServiceKey, List<Instance>> entry : session.getAllPublishers().entrySet()) {
                ServiceKey serviceKey = entry.getKey();
                for (Instance inst : entry.getValue()) {
                    long lastBeat = inst.getLastBeat();
                    if (lastBeat > 0 && now - lastBeat > timeoutMs) {
                        expired.add(new ExpiredInstance(serviceKey, inst.getIp(), inst.getPort()));
                    }
                }
            }
        }
        return expired;
    }

    /**
     * Remove a specific instance identified by ip:port from the given service.
     * Notifies subscribers if the instance was actually removed.
     */
    public void removeInstanceByIpPort(ServiceKey serviceKey, String ip, int port) {
        Set<String> connectionIds = publisherIndexes.get(serviceKey);
        if (connectionIds == null) {
            return;
        }
        for (String connectionId : connectionIds) {
            ClientSession session = connectionManager.getClientSession(connectionId);
            if (session == null) {
                continue;
            }
            for (Instance inst : session.getInstances(serviceKey)) {
                if (ip.equals(inst.getIp()) && port == inst.getPort()) {
                    session.removeInstance(serviceKey, ip, port);
                    if (session.getInstances(serviceKey).isEmpty()) {
                        connectionIds.remove(connectionId);
                        if (connectionIds.isEmpty()) {
                            publisherIndexes.remove(serviceKey);
                        }
                    }
                    invalidateServiceCache(serviceKey);
                    log.info("[harbor] expired instance removed: {} -> {}:{}", serviceKey, ip, port);
                    checkAndCleanEmptyService(serviceKey, true);
                    return;
                }
            }
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
            checkAndCleanEmptyService(serviceKey, true);
        }
        return totalRemoved;
    }

    /**
     * Descriptor for an expired instance returned by {@link #getExpiredInstances}.
     */
    public record ExpiredInstance(ServiceKey serviceKey, String ip, int port) {}

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
     * Apply a snapshot from a peer node.
     * In the client-centric model, snapshots are lists of ClientSyncData.
     */
    public void applySnapshot(List<ClientSyncData> clientDataList) {
        if (clientDataList == null) {
            return;
        }
        for (ClientSyncData data : clientDataList) {
            applyClientSyncData(data);
        }
        log.info("[harbor] naming snapshot applied, {} clients", clientDataList.size());
    }

    // ========================================================================
    // Client-level Distro support
    // ========================================================================

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

        // A re-sync replaces the previous copy: drop this client from exactly the
        // services the old replica held (existing is non-null and, per the check
        // above, non-native). First-time syncs have nothing to undo.
        if (existing != null) {
            for (ServiceKey previous : existing.getAllPublishedServices()) {
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
                instance.setConnectionId(connectionId);
                session.addInstance(serviceKey, instance);
                publisherIndexes.computeIfAbsent(serviceKey, k -> new CopyOnWriteArraySet<>())
                        .add(connectionId);
                invalidateServiceCache(serviceKey);
            }
        }

        // Set the authoritative revision AFTER all addInstance calls,
        // because each addInstance triggers recalculateRevision() which
        // would otherwise overwrite the source revision we just received.
        session.setRevision(data.getRevision());

        // Receiving the own-state of the owning node IS the confirmation.
        session.markOwnerConfirmed();
        connectionManager.putClientSession(connectionId, session);
        log.info("[harbor] applied client sync: {} (publishers={})",
                connectionId, session.getTotalInstanceCount());
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
            checkAndCleanEmptyService(serviceKey, true);
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
     * Retire a service that has neither publishers nor subscribers, announcing the
     * final (empty) state first so subscribers drop it.
     * <p>
     * Whether the caller also announces a SURVIVING service is decided by
     * {@code dataChanged}, because this check runs from places that changed nothing
     * anyone routes by: the periodic sweep (every 5 s, for every service) and an
     * unsubscribe (one receiver leaving, the list the others get is unchanged).
     * Announcing there re-pushed every service in full every sweep — Nacos pushes on
     * change, so a surviving service is announced only by callers that added or
     * removed one of its instances.
     *
     * @param dataChanged {@code true} when the caller mutated this service's instance
     *                    set, {@code false} for a pure index/lifetime cleanup pass
     */
    private void checkAndCleanEmptyService(ServiceKey serviceKey, boolean dataChanged) {
        Set<String> publishers = publisherIndexes.get(serviceKey);
        Set<String> subscribers = subscriberIndexes.get(serviceKey);
        boolean noPublishers = publishers == null || publishers.isEmpty();
        boolean noSubscribers = subscribers == null || subscribers.isEmpty();

        if (noPublishers && noSubscribers) {
            // Notify subscribers with empty service info before cleaning up
            changeListener.onServiceChange(serviceKey);
            // Remove from all indexes
            publisherIndexes.remove(serviceKey);
            subscriberIndexes.remove(serviceKey);
            invalidateServiceCache(serviceKey);
            log.info("[harbor] empty service cleaned: {}", serviceKey);
        } else if (dataChanged) {
            changeListener.onServiceChange(serviceKey);
        }
    }

    /**
     * Periodically clean up empty services (no publishers and no subscribers).
     * Called by {@link HealthCheckManager} after instance/connection cleanup.
     */
    public void cleanEmptyServices() {
        Set<ServiceKey> allKeys = new HashSet<>();
        allKeys.addAll(publisherIndexes.keySet());
        allKeys.addAll(subscriberIndexes.keySet());
        for (ServiceKey serviceKey : allKeys) {
            // idle sweep must not re-push every service every cycle
            checkAndCleanEmptyService(serviceKey, false);
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
