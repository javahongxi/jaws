package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory service instance storage for the naming service.
 * <p>
 * Follows the Nacos dual-layer model:
 * <ul>
 *   <li><b>Layer 1 — ClientSession</b> (source of truth): each client connection
 *       owns its published instances and subscriptions.  Instance data lives
 *       exclusively here.</li>
 *   <li><b>Layer 2 — lightweight indexes</b>: {@code publisherIndexes} maps
 *       serviceKey → Set&lt;clientId&gt; and {@code subscriberIndexes} maps
 *       serviceKey → Set&lt;clientId&gt;.  These are ID-only reverse indexes
 *       used to locate which clients are involved in a service.</li>
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
     * Publisher reverse index: serviceKey → Set&lt;clientId&gt;.
     * Only stores client IDs, not Instance data — matching Nacos's
     * {@code ClientServiceIndexesManager.publisherIndexes}.
     */
    private final Map<String, Set<String>> publisherIndexes = new ConcurrentHashMap<>();

    /**
     * Subscriber reverse index: serviceKey → Set&lt;clientId&gt;.
     */
    private final Map<String, Set<String>> subscriberIndexes = new ConcurrentHashMap<>();

    /**
     * Read cache: serviceKey → aggregated {@link ServiceInfo}.
     * Matches Nacos {@code ServiceStorage.serviceDataIndexes}.
     * Invalidated on every mutation; populated on read via
     * {@link #buildServiceInfo}.
     */
    private final Map<String, ServiceInfo> serviceDataIndexes = new ConcurrentHashMap<>();

    private final SubscriberListener listener;
    private final ConnectionManager connectionManager;

    public ServiceStorage(SubscriberListener listener, ConnectionManager connectionManager) {
        this.listener = listener;
        this.connectionManager = connectionManager;
    }

    // ========================================================================
    // Instance registration / deregistration
    // ========================================================================

    /**
     * Register an instance for the given service.
     * <p>
     * Instance data is written to the {@link ClientSession} (source of truth);
     * only the clientId is added to the publisher index.
     *
     * @param connectionId the gRPC connectionId that registered this instance;
     *                     used to scope deregistration to the owning connection
     *                     so that other connections from the same clientIp are unaffected.
     */
    public void registerInstance(String namespace, String group, String serviceName,
                                 Instance instance, String connectionId) {
        String key = buildKey(namespace, group, serviceName);

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

        // Add clientId to publisher index
        publisherIndexes.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>())
                .add(connectionId);

        invalidateServiceCache(key);
        log.info("[harbor] instance registered: {} -> {}:{}",
                key, instance.getIp(), instance.getPort());
        notifySubscribers(key, namespace, group, serviceName);
    }

    /**
     * Deregister an instance from the given service.
     */
    public void deregisterInstance(String namespace, String group, String serviceName,
                                   Instance instance, String connectionId) {
        String key = buildKey(namespace, group, serviceName);
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
                    Set<String> clientIds = publisherIndexes.get(key);
                    if (clientIds != null) {
                        clientIds.remove(connectionId);
                        if (clientIds.isEmpty()) {
                            publisherIndexes.remove(key);
                        }
                    }
                    log.info("[harbor] instance deregistered: {} -> {}:{}", key, ip, port);
                    notifySubscribers(key, namespace, group, serviceName);
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
        return aggregateInstances(buildKey(namespace, group, serviceName));
    }

    /**
     * Aggregate instances for a serviceKey from all ClientSession publishers.
     */
    private List<Instance> aggregateInstances(String serviceKey) {
        Set<String> clientIds = publisherIndexes.get(serviceKey);
        if (clientIds == null || clientIds.isEmpty()) {
            return List.of();
        }
        List<Instance> result = new ArrayList<>();
        for (String clientId : clientIds) {
            ClientSession session = connectionManager.getClientSession(clientId);
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
        String key = buildKey(namespace, group, serviceName);
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
        String key = buildKey(namespace, group, serviceName);
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
    }

    /**
     * Remove all subscriber entries for a given connection (on disconnect).
     */
    public void removeAllSubscribersForConnection(String connectionId) {
        ClientSession session = connectionManager.getClientSession(connectionId);
        if (session != null) {
            for (String serviceKey : session.getAllSubscribedServices()) {
                Set<String> subscribers = subscriberIndexes.get(serviceKey);
                if (subscribers != null) {
                    subscribers.remove(connectionId);
                    if (subscribers.isEmpty()) {
                        subscriberIndexes.remove(serviceKey);
                    }
                }
            }
        } else {
            // Fallback: scan all subscriber entries
            for (Map.Entry<String, Set<String>> entry : subscriberIndexes.entrySet()) {
                entry.getValue().remove(connectionId);
                if (entry.getValue().isEmpty()) {
                    subscriberIndexes.remove(entry.getKey());
                }
            }
        }
    }

    // ========================================================================
    // Service listing & info
    // ========================================================================

    /**
     * List all registered service names.
     */
    public List<String> listServices(String namespace, String group) {
        String prefix = namespace + "@@" + group + "@@";
        List<String> result = new ArrayList<>();
        for (String key : publisherIndexes.keySet()) {
            if (key.startsWith(prefix)) {
                String serviceName = key.substring(prefix.length());
                result.add(serviceName);
            }
        }
        return result;
    }

    /**
     * Build a {@link ServiceInfo} for the given service, including all instances.
     */
    public ServiceInfo buildServiceInfo(String namespace, String group, String serviceName) {
        String key = buildKey(namespace, group, serviceName);
        ServiceInfo cached = serviceDataIndexes.get(key);
        if (cached != null) {
            return cached;
        }

        List<Instance> instances = aggregateInstances(key);
        String groupedName = group + "@@" + serviceName;

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

    private void notifySubscribers(String key, String namespace, String group, String serviceName) {
        Set<String> subscribers = subscriberIndexes.get(key);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        ServiceInfo serviceInfo = buildServiceInfo(namespace, group, serviceName);
        for (String connId : subscribers) {
            listener.onServiceChange(connId, namespace, group, serviceName, serviceInfo);
        }
    }

    // ========================================================================
    // Heartbeat health check
    // ========================================================================

    /**
     * Update the last heartbeat for all instances registered from the given client IP.
     * This follows Nacos's connection-level health check model: any request from a
     * client refreshes the heartbeat for all its registered instances.
     *
     * @param clientIp the client IP from Payload metadata
     */
    public void updateHeartbeatByClientIp(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ClientSession session : connectionManager.allClientSessions()) {
            for (List<Instance> instances : session.getAllPublishers().values()) {
                for (Instance inst : instances) {
                    if (clientIp.equals(inst.getIp())) {
                        inst.setLastBeat(now);
                    }
                }
            }
        }
    }

    /**
     * Find all ephemeral instances whose last heartbeat exceeds the timeout.
     *
     * @param timeoutMs the heartbeat timeout in milliseconds
     * @return list of expired instance descriptors (serviceKey + ip + port)
     */
    public List<ExpiredInstance> getExpiredInstances(long timeoutMs) {
        long now = System.currentTimeMillis();
        List<ExpiredInstance> expired = new ArrayList<>();
        for (ClientSession session : connectionManager.allClientSessions()) {
            for (Map.Entry<String, List<Instance>> entry : session.getAllPublishers().entrySet()) {
                String serviceKey = entry.getKey();
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
    public void removeInstanceByIpPort(String serviceKey, String ip, int port) {
        Set<String> clientIds = publisherIndexes.get(serviceKey);
        if (clientIds == null) {
            return;
        }
        for (String clientId : clientIds) {
            ClientSession session = connectionManager.getClientSession(clientId);
            if (session == null) {
                continue;
            }
            for (Instance inst : session.getInstances(serviceKey)) {
                if (ip.equals(inst.getIp()) && port == inst.getPort()) {
                    session.removeInstance(serviceKey, ip, port);
                    if (session.getInstances(serviceKey).isEmpty()) {
                        clientIds.remove(clientId);
                        if (clientIds.isEmpty()) {
                            publisherIndexes.remove(serviceKey);
                        }
                    }
                    invalidateServiceCache(serviceKey);
                    String[] parts = serviceKey.split("@@", 3);
                    if (parts.length == 3) {
                        log.info("[harbor] expired instance removed: {} -> {}:{}", serviceKey, ip, port);
                        notifySubscribers(serviceKey, parts[0], parts[1], parts[2]);
                    }
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
        for (Map.Entry<String, List<Instance>> entry : session.getAllPublishers().entrySet()) {
            String serviceKey = entry.getKey();
            int count = entry.getValue().size();
            totalRemoved += count;
            session.removeAllInstances(serviceKey);
            // Remove clientId from publisher index
            Set<String> clientIds = publisherIndexes.get(serviceKey);
            if (clientIds != null) {
                clientIds.remove(connectionId);
                if (clientIds.isEmpty()) {
                    publisherIndexes.remove(serviceKey);
                }
            }
            invalidateServiceCache(serviceKey);
            String[] parts = serviceKey.split("@@", 3);
            if (parts.length == 3) {
                log.info("[harbor] instance(s) deregistered on disconnect: {} -> connId={} ({} instance(s))",
                        serviceKey, connectionId, count);
                notifySubscribers(serviceKey, parts[0], parts[1], parts[2]);
            }
        }
        return totalRemoved;
    }

    /**
     * Descriptor for an expired instance returned by {@link #getExpiredInstances}.
     */
    public record ExpiredInstance(String serviceKey, String ip, int port) {}

    // ========================================================================
    // Distro protocol support
    // ========================================================================

    /**
     * Get all instance data aggregated from ClientSessions, grouped by serviceKey.
     * Used by the HTTP management API.
     */
    public Map<String, List<Instance>> getAllInstanceData() {
        Map<String, List<Instance>> result = new HashMap<>();
        for (String serviceKey : publisherIndexes.keySet()) {
            List<Instance> instances = aggregateInstances(serviceKey);
            if (!instances.isEmpty()) {
                result.put(serviceKey, instances);
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
     * Used by Distro CHANGE sync to send the full client state to peers.
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
        for (Map.Entry<String, List<Instance>> entry : session.getAllPublishers().entrySet()) {
            for (Instance inst : entry.getValue()) {
                serviceKeys.add(entry.getKey());
                instances.add(inst);
            }
        }
        return new ClientSyncData(
                connectionId,
                serviceKeys,
                instances,
                new ArrayList<>(session.getAllSubscribedServices()),
                session.getRevision()
        );
    }

    /**
     * Apply a {@link ClientSyncData} received from a peer via Distro sync.
     * Creates or replaces a local ClientSession (marked as non-native) and
     * updates the publisher/subscriber indexes with the clientId.
     * <p>
     * Uses full-replacement semantics: the old client data is removed and
     * replaced with the incoming, matching Nacos's
     * {@code upgradeClient()} behaviour.
     */
    public void applyClientSyncData(ClientSyncData data) {
        if (data == null || data.getClientId() == null) {
            return;
        }
        String clientId = data.getClientId();

        // Skip if this client is a native client (the node's own connection).
        // Native clients are authoritative locally and must never be overwritten
        // by a peer's potentially stale copy.
        ClientSession existing = connectionManager.getClientSession(clientId);
        if (existing != null && existing.isNativeClient()) {
            log.debug("[harbor] skipping client sync for native client: {}", clientId);
            return;
        }

        // Remove old index entries for this client
        removeClientFromIndexes(clientId);

        // Create or update the ClientSession
        ClientSession session = new ClientSession(clientId);
        session.setNativeClient(false);
        session.setRevision(data.getRevision());

        // Apply publishers — write to ClientSession + add clientId to publisherIndexes
        List<String> serviceKeys = data.getServiceKeys();
        List<Instance> instances = data.getInstances();
        if (serviceKeys != null && instances != null) {
            for (int i = 0; i < serviceKeys.size() && i < instances.size(); i++) {
                String serviceKey = serviceKeys.get(i);
                Instance instance = instances.get(i);
                instance.setConnectionId(clientId);
                session.addInstance(serviceKey, instance);
                publisherIndexes.computeIfAbsent(serviceKey, k -> new CopyOnWriteArraySet<>())
                        .add(clientId);
                invalidateServiceCache(serviceKey);
            }
        }

        // Apply subscribers — write to ClientSession + add clientId to subscriberIndexes
        List<String> subscriberKeys = data.getSubscriberKeys();
        if (subscriberKeys != null) {
            for (String serviceKey : subscriberKeys) {
                session.addSubscriber(serviceKey);
                subscriberIndexes.computeIfAbsent(serviceKey, k -> new CopyOnWriteArraySet<>())
                        .add(clientId);
            }
        }

        connectionManager.putClientSession(clientId, session);
        log.info("[harbor] applied client sync: {} (publishers={}, subscribers={})",
                clientId, session.getTotalInstanceCount(), session.getAllSubscribedServices().size());
    }

    /**
     * Remove a synced (non-native) client and all its index entries.
     * Called when a Distro DELETE is received for a connection.
     */
    public void removeSyncedClient(String clientId) {
        ClientSession session = connectionManager.getClientSession(clientId);
        if (session == null) {
            return;
        }
        // Notify subscribers before removing index entries
        for (String serviceKey : session.getAllPublishedServices()) {
            invalidateServiceCache(serviceKey);
            String[] parts = serviceKey.split("@@", 3);
            if (parts.length == 3) {
                notifySubscribers(serviceKey, parts[0], parts[1], parts[2]);
            }
        }
        removeClientFromIndexes(clientId);
        session.release();
        connectionManager.removeClientSession(clientId);
        log.info("[harbor] removed synced client: {}", clientId);
    }

    /**
     * Remove a clientId from both publisher and subscriber indexes.
     * Cleans up empty entries.
     */
    private void removeClientFromIndexes(String clientId) {
        for (Map.Entry<String, Set<String>> entry : publisherIndexes.entrySet()) {
            entry.getValue().remove(clientId);
            if (entry.getValue().isEmpty()) {
                publisherIndexes.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, Set<String>> entry : subscriberIndexes.entrySet()) {
            entry.getValue().remove(clientId);
            if (entry.getValue().isEmpty()) {
                subscriberIndexes.remove(entry.getKey());
            }
        }
    }

    private void invalidateServiceCache(String serviceKey) {
        serviceDataIndexes.remove(serviceKey);
    }

    private static String buildKey(String namespace, String group, String serviceName) {
        return namespace + "@@" + group + "@@" + serviceName;
    }

    /**
     * Callback for notifying subscribers of service changes.
     */
    @FunctionalInterface
    public interface SubscriberListener {
        void onServiceChange(String connectionId, String namespace, String group,
                             String serviceName, ServiceInfo serviceInfo);
    }
}
