package org.hongxi.jaws.harbor;

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
 * Stores ephemeral instances registered by nacos-client and manages
 * per-service subscriber sets. When instances change (register/deregister),
 * all subscribers of the affected service are notified via the
 * {@link SubscriberListener} callback.
 * <p>
 * Thread-safe: all mutations and reads are safe under concurrent access.
 *
 * @author shenhongxi
 */
public class ServiceStorage {

    private static final Logger log = LoggerFactory.getLogger(ServiceStorage.class);

    /**
     * Key: "namespace@@group@@serviceName"
     * Value: list of registered instances
     */
    private final Map<String, List<Instance>> instanceMap = new ConcurrentHashMap<>();

    /**
     * Key: "namespace@@group@@serviceName"
     * Value: set of subscriber connection IDs
     */
    private final Map<String, Set<String>> subscriberMap = new ConcurrentHashMap<>();

    private final SubscriberListener listener;

    public ServiceStorage(SubscriberListener listener) {
        this.listener = listener;
    }

    /**
     * Register an instance for the given service.
     *
     * @param connectionId the gRPC connectionId that registered this instance;
     *                     used to scope deregistration to the owning connection
     *                     so that other connections from the same clientIp are unaffected.
     */
    public void registerInstance(String namespace, String group, String serviceName,
                                 Instance instance, String connectionId) {
        String key = buildKey(namespace, group, serviceName);
        instanceMap.compute(key, (k, existing) -> {
            if (existing == null) {
                existing = new ArrayList<>();
            }
            // Replace existing instance with same ip#port, or add new
            String ip = instance.getIp();
            int port = instance.getPort();
            existing.removeIf(inst ->
                    ip.equals(inst.getIp()) && port == inst.getPort());

            // Add registration time and last beat time for health check
            long currentTime = System.currentTimeMillis();
            instance.setRegisterTime(currentTime);
            instance.setLastBeat(currentTime);
            instance.setConnectionId(connectionId);

            existing.add(instance);
            return existing;
        });
        log.info("[harbor] instance registered: {} -> {}:{}", key, instance.getIp(), instance.getPort());
        notifySubscribers(key, namespace, group, serviceName);
    }

    /**
     * Deregister an instance from the given service.
     */
    public void deregisterInstance(String namespace, String group, String serviceName,
                                   Instance instance) {
        String key = buildKey(namespace, group, serviceName);
        String ip = instance.getIp();
        int port = instance.getPort();
        instanceMap.computeIfPresent(key, (k, existing) -> {
            existing.removeIf(inst ->
                    ip.equals(inst.getIp()) && port == inst.getPort());
            return existing.isEmpty() ? null : existing;
        });
        log.info("[harbor] instance deregistered: {} -> {}:{}", key, ip, port);
        notifySubscribers(key, namespace, group, serviceName);
    }

    /**
     * Query all instances for the given service.
     */
    public List<Instance> getInstances(String namespace, String group, String serviceName) {
        String key = buildKey(namespace, group, serviceName);
        List<Instance> instances = instanceMap.get(key);
        return instances != null ? List.copyOf(instances) : List.of();
    }

    /**
     * Add a subscriber for the given service.
     */
    public void addSubscriber(String namespace, String group, String serviceName,
                              String connectionId) {
        String key = buildKey(namespace, group, serviceName);
        subscriberMap.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>())
                .add(connectionId);
        log.debug("[harbor] subscriber added: conn={} for {}", connectionId, key);
    }

    /**
     * Remove a subscriber from the given service.
     */
    public void removeSubscriber(String namespace, String group, String serviceName,
                                 String connectionId) {
        String key = buildKey(namespace, group, serviceName);
        Set<String> subscribers = subscriberMap.get(key);
        if (subscribers != null) {
            subscribers.remove(connectionId);
            if (subscribers.isEmpty()) {
                subscriberMap.remove(key);
            }
        }
    }

    /**
     * Remove all subscriber entries for a given connection (on disconnect).
     */
    public void removeAllSubscribersForConnection(String connectionId) {
        for (Map.Entry<String, Set<String>> entry : subscriberMap.entrySet()) {
            entry.getValue().remove(connectionId);
            if (entry.getValue().isEmpty()) {
                subscriberMap.remove(entry.getKey());
            }
        }
    }

    /**
     * List all registered service names.
     */
    public List<String> listServices(String namespace, String group) {
        String prefix = namespace + "@@" + group + "@@";
        List<String> result = new ArrayList<>();
        for (String key : instanceMap.keySet()) {
            if (key.startsWith(prefix)) {
                // Extract service name from "namespace@@group@@serviceName"
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
        List<Instance> instances = getInstances(namespace, group, serviceName);
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
        return info;
    }

    private void notifySubscribers(String key, String namespace, String group,
                                   String serviceName) {
        Set<String> subscribers = subscriberMap.get(key);
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
     * @return number of instances whose heartbeat was updated
     */
    public int updateHeartbeatByClientIp(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return 0;
        }
        int count = 0;
        long now = System.currentTimeMillis();
        for (List<Instance> instances : instanceMap.values()) {
            for (Instance inst : instances) {
                if (clientIp.equals(inst.getIp())) {
                    inst.setLastBeat(now);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Update the last heartbeat timestamp for a specific instance.
     * Called when the instance sends any request (register, subscribe, health check).
     *
     * @return true if the instance was found and updated
     */
    public boolean updateInstanceHeartbeat(String namespace, String group, String serviceName,
                                           String ip, int port) {
        String key = buildKey(namespace, group, serviceName);
        List<Instance> instances = instanceMap.get(key);
        if (instances != null) {
            for (Instance inst : instances) {
                if (ip.equals(inst.getIp()) && port == inst.getPort()) {
                    inst.setLastBeat(System.currentTimeMillis());
                    return true;
                }
            }
        }
        return false;
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
        for (Map.Entry<String, List<Instance>> entry : instanceMap.entrySet()) {
            String serviceKey = entry.getKey();
            for (Instance inst : entry.getValue()) {
                long lastBeat = inst.getLastBeat();
                if (lastBeat > 0 && now - lastBeat > timeoutMs) {
                    expired.add(new ExpiredInstance(
                            serviceKey,
                            inst.getIp(),
                            inst.getPort()));
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
        instanceMap.computeIfPresent(serviceKey, (k, existing) -> {
            existing.removeIf(inst ->
                    ip.equals(inst.getIp()) && port == inst.getPort());
            return existing.isEmpty() ? null : existing;
        });
        // Parse serviceKey back to namespace/group/serviceName for notification
        String[] parts = serviceKey.split("@@", 3);
        if (parts.length == 3) {
            log.info("[harbor] expired instance removed: {} -> {}:{}", serviceKey, ip, port);
            notifySubscribers(serviceKey, parts[0], parts[1], parts[2]);
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
        int totalRemoved = 0;
        for (Map.Entry<String, List<Instance>> entry : instanceMap.entrySet()) {
            String serviceKey = entry.getKey();
            List<Instance> instances = entry.getValue();
            int before = instances.size();
            instances.removeIf(inst -> connectionId.equals(inst.getConnectionId()));
            int removed = before - instances.size();
            if (removed > 0) {
                totalRemoved += removed;
                if (instances.isEmpty()) {
                    instanceMap.remove(serviceKey);
                }
                // Parse serviceKey and notify subscribers
                String[] parts = serviceKey.split("@@", 3);
                if (parts.length == 3) {
                    log.info("[harbor] instance(s) deregistered on disconnect: {} -> connId={} ({} instance(s))",
                            serviceKey, connectionId, removed);
                    notifySubscribers(serviceKey, parts[0], parts[1], parts[2]);
                }
            }
        }
        return totalRemoved;
    }

    /**
     * Remove all instances registered from the given client IP across all services.
     * Called when a client connection is closed (bi-stream completed/error).
     * Notifies subscribers for each affected service.
     *
     * @param clientIp the client IP from Payload metadata
     * @return total number of instances removed
     * @deprecated use {@link #deregisterInstancesByConnectionId} to avoid
     *             removing instances from other connections on the same clientIp
     */
    @Deprecated
    public int deregisterInstancesByClientIp(String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return 0;
        }
        int totalRemoved = 0;
        for (Map.Entry<String, List<Instance>> entry : instanceMap.entrySet()) {
            String serviceKey = entry.getKey();
            List<Instance> instances = entry.getValue();
            int before = instances.size();
            instances.removeIf(inst -> clientIp.equals(inst.getIp()));
            int removed = before - instances.size();
            if (removed > 0) {
                totalRemoved += removed;
                if (instances.isEmpty()) {
                    instanceMap.remove(serviceKey);
                }
                // Parse serviceKey and notify subscribers
                String[] parts = serviceKey.split("@@", 3);
                if (parts.length == 3) {
                    log.info("[harbor] instance(s) deregistered on disconnect: {} -> {} ({} instance(s))",
                            serviceKey, clientIp, removed);
                    notifySubscribers(serviceKey, parts[0], parts[1], parts[2]);
                }
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
     * Get all instance data as a flat map for Distro snapshot/sync.
     *
     * @return copy of the internal instance map
     */
    public Map<String, List<Instance>> getAllInstanceData() {
        return Map.copyOf(instanceMap);
    }

    /**
     * Apply a snapshot from a peer node — merges into local storage
     * without overwriting existing keys.
     */
    public void applySnapshot(Map<String, List<Instance>> snapshot) {
        for (Map.Entry<String, List<Instance>> entry : snapshot.entrySet()) {
            instanceMap.putIfAbsent(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        log.info("[harbor] naming snapshot applied, keys={}", instanceMap.size());
    }

    /**
     * Build a verify-data map: serviceKey → count of instances (simple checksum).
     */
    public Map<String, Integer> getVerifyChecksums() {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, List<Instance>> entry : instanceMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return result;
    }

    private static String buildKey(String namespace, String group, String serviceName) {
        return namespace + "@@" + group + "@@" + serviceName;
    }

    /**
     * Callback for notifying subscribers of service changes.
     */
    public interface SubscriberListener {
        void onServiceChange(String connectionId, String namespace, String group,
                             String serviceName, ServiceInfo serviceInfo);
    }
}
