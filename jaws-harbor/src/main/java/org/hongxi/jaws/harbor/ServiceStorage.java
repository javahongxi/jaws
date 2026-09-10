package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
     * Value: list of registered instances (as JSON objects)
     */
    private final Map<String, List<JSONObject>> instanceMap = new ConcurrentHashMap<>();

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
     */
    public void registerInstance(String namespace, String group, String serviceName,
                                 JSONObject instance) {
        String key = buildKey(namespace, group, serviceName);
        instanceMap.compute(key, (k, existing) -> {
            if (existing == null) {
                existing = new ArrayList<>();
            }
            // Replace existing instance with same ip#port, or add new
            String ip = instance.getString("ip");
            int port = instance.getIntValue("port", 0);
            existing.removeIf(inst ->
                    ip.equals(inst.getString("ip")) && port == inst.getIntValue("port", 0));
            existing.add(instance);
            return existing;
        });
        log.info("[harbor] instance registered: {} -> {}:{}", key, ip(instance), port(instance));
        notifySubscribers(key, namespace, group, serviceName);
    }

    /**
     * Deregister an instance from the given service.
     */
    public void deregisterInstance(String namespace, String group, String serviceName,
                                   JSONObject instance) {
        String key = buildKey(namespace, group, serviceName);
        String ip = instance.getString("ip");
        int port = instance.getIntValue("port", 0);
        instanceMap.computeIfPresent(key, (k, existing) -> {
            existing.removeIf(inst ->
                    ip.equals(inst.getString("ip")) && port == inst.getIntValue("port", 0));
            return existing.isEmpty() ? null : existing;
        });
        log.info("[harbor] instance deregistered: {} -> {}:{}", key, ip, port);
        notifySubscribers(key, namespace, group, serviceName);
    }

    /**
     * Query all instances for the given service.
     */
    public List<JSONObject> getInstances(String namespace, String group, String serviceName) {
        String key = buildKey(namespace, group, serviceName);
        List<JSONObject> instances = instanceMap.get(key);
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
     * Build a ServiceInfo JSON object for the given service, including all instances.
     */
    public JSONObject buildServiceInfo(String namespace, String group, String serviceName) {
        List<JSONObject> instances = getInstances(namespace, group, serviceName);
        String groupedName = group + "@@" + serviceName;

        JSONObject serviceInfo = new JSONObject();
        serviceInfo.put("name", groupedName);
        serviceInfo.put("groupName", group);
        serviceInfo.put("clusters", "");
        serviceInfo.put("cacheMillis", 10000);
        serviceInfo.put("lastRefTime", System.currentTimeMillis());
        serviceInfo.put("checksum", "");
        serviceInfo.put("allIPs", false);
        serviceInfo.put("reachProtectionThreshold", false);

        JSONArray hosts = new JSONArray();
        for (JSONObject inst : instances) {
            JSONObject host = new JSONObject();
            host.put("ip", inst.getString("ip"));
            host.put("port", inst.getIntValue("port"));
            host.put("weight", inst.containsKey("weight") ? inst.getDoubleValue("weight") : 1.0);
            host.put("healthy", inst.getBooleanValue("healthy", true));
            host.put("enabled", inst.getBooleanValue("enabled", true));
            host.put("ephemeral", inst.getBooleanValue("ephemeral", true));
            host.put("serviceName", groupedName);
            host.put("instanceId", inst.getString("instanceId"));
            host.put("metadata", inst.getJSONObject("metadata") != null
                    ? inst.getJSONObject("metadata") : new JSONObject());
            hosts.add(host);
        }
        serviceInfo.put("hosts", hosts);
        return serviceInfo;
    }

    private void notifySubscribers(String key, String namespace, String group,
                                   String serviceName) {
        Set<String> subscribers = subscriberMap.get(key);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        JSONObject serviceInfo = buildServiceInfo(namespace, group, serviceName);
        for (String connId : subscribers) {
            listener.onServiceChange(connId, namespace, group, serviceName, serviceInfo);
        }
    }

    // ========================================================================
    // Distro protocol support
    // ========================================================================

    /**
     * Get all instance data as a flat map for Distro snapshot/sync.
     *
     * @return copy of the internal instance map
     */
    public Map<String, List<JSONObject>> getAllInstanceData() {
        return Map.copyOf(instanceMap);
    }

    /**
     * Apply a snapshot from a peer node — merges into local storage
     * without overwriting existing keys.
     */
    public void applySnapshot(Map<String, List<JSONObject>> snapshot) {
        for (Map.Entry<String, List<JSONObject>> entry : snapshot.entrySet()) {
            instanceMap.putIfAbsent(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        log.info("[harbor] naming snapshot applied, keys={}", instanceMap.size());
    }

    /**
     * Build a verify-data map: serviceKey → count of instances (simple checksum).
     */
    public Map<String, Integer> getVerifyChecksums() {
        Map<String, Integer> result = new java.util.HashMap<>();
        for (Map.Entry<String, List<JSONObject>> entry : instanceMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return result;
    }

    private static String buildKey(String namespace, String group, String serviceName) {
        return namespace + "@@" + group + "@@" + serviceName;
    }

    private static String ip(JSONObject instance) {
        return instance.getString("ip");
    }

    private static int port(JSONObject instance) {
        return instance.getIntValue("port", 0);
    }

    /**
     * Callback for notifying subscribers of service changes.
     */
    public interface SubscriberListener {
        void onServiceChange(String connectionId, String namespace, String group,
                             String serviceName, JSONObject serviceInfo);
    }
}
