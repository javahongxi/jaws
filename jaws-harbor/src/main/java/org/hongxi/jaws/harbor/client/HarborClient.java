package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.harbor.model.request.InstanceRequest;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.model.request.ServiceListRequest;
import org.hongxi.jaws.harbor.model.request.ServiceQueryRequest;
import org.hongxi.jaws.harbor.model.request.SubscribeServiceRequest;
import org.hongxi.jaws.harbor.model.response.InstanceResponse;
import org.hongxi.jaws.harbor.model.response.QueryServiceResponse;
import org.hongxi.jaws.harbor.model.response.ServiceListResponse;
import org.hongxi.jaws.harbor.model.response.SubscribeServiceResponse;
import org.hongxi.jaws.harbor.proto.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Native client of a harbor service registry, speaking the same wire protocol
 * the server answers for nacos-client.
 * <p>
 * Two things it deliberately does not do:
 * <ul>
 *   <li>It keeps no disk cache. Registry-level file caching and fail-back retry
 *       already live in the abstraction a consumer uses, and duplicating them
 *       here would put two sources of truth on the same recovery.</li>
 *   <li>It never re-registers behind the server's back without being asked:
 *       the replay after a reconnect replays exactly what this client holds,
 *       because harbor retires everything the previous connection owned.</li>
 * </ul>
 * Listener callbacks run on a single notifier thread, so a slow listener cannot
 * stall the notification stream.
 *
 * @author shenhongxi
 */
public class HarborClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HarborClient.class);

    private final HarborClientConfig config;
    private final Map<ServiceKey, InstanceRedoData> registrations = new ConcurrentHashMap<>();
    private final Map<ServiceKey, ServiceSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService notifier;
    private final HarborConnection connection;

    public HarborClient(String host, int port) {
        this(HarborClientConfig.of(host, port));
    }

    public HarborClient(HarborClientConfig config) {
        this.config = config;
        this.notifier = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "harbor-client-notifier");
            thread.setDaemon(true);
            return thread;
        });
        this.connection = new HarborConnection(config, this::onPushFrame, this::replayOwnedState);
        this.connection.start();
    }

    // ========================================================================
    // Registration
    // ========================================================================

    public void registerInstance(String serviceName, Instance instance) {
        registerInstance(serviceName, config.defaultGroup(), instance);
    }

    public void registerInstance(String serviceName, String groupName, Instance instance) {
        ServiceKey key = keyOf(serviceName, groupName);
        // Cached before the call, with intent set: a reconnect that follows a lost
        // reply must still replay what the caller asked for. Re-registering the
        // same service replaces the payload, so the replay sends the newest value.
        InstanceRedoData entry = registrations.computeIfAbsent(key,
                found -> new InstanceRedoData(instance));
        entry.setInstance(instance);
        entry.expectRegistered();
        sendInstanceRequest(key, entry.instance(), HarborProtocol.REGISTER_INSTANCE);
        entry.registered();
    }

    public void deregisterInstance(String serviceName, Instance instance) {
        deregisterInstance(serviceName, config.defaultGroup(), instance);
    }

    public void deregisterInstance(String serviceName, String groupName, Instance instance) {
        ServiceKey key = keyOf(serviceName, groupName);
        InstanceRedoData entry = registrations.get(key);
        if (entry == null) {
            // Nothing was owed to the registry here; send anyway so a caller that
            // lost track of its own state cannot leave an instance behind.
            sendInstanceRequest(key, instance, HarborProtocol.DEREGISTER_INSTANCE);
            return;
        }
        // Marked before the request: if the reply is lost the removal stays owed,
        // and the next replay pass completes it instead of ghosting the instance.
        entry.expectUnregistered();
        sendInstanceRequest(key, entry.instance(), HarborProtocol.DEREGISTER_INSTANCE);
        entry.unregistered();
        // Confirmed spent. Nacos drops the entry on the next redo pass via
        // RedoType.REMOVE; there is no periodic pass here, so it goes now.
        registrations.remove(key);
    }

    private void sendInstanceRequest(ServiceKey key, Instance instance, String operation) {
        InstanceRequest request = new InstanceRequest();
        request.setNamespace(key.namespace());
        request.setGroupName(key.group());
        request.setServiceName(key.name());
        request.setType(operation);
        request.setInstance(instance);
        connection.call(request, InstanceResponse.class);
    }

    // ========================================================================
    // Discovery
    // ========================================================================

    public List<Instance> getInstances(String serviceName) {
        return getInstances(serviceName, config.defaultGroup(), false);
    }

    public List<Instance> getInstances(String serviceName, boolean healthyOnly) {
        return getInstances(serviceName, config.defaultGroup(), healthyOnly);
    }

    public List<Instance> getInstances(String serviceName, String groupName, boolean healthyOnly) {
        ServiceQueryRequest request = new ServiceQueryRequest();
        request.setNamespace(keyOf(serviceName, groupName).namespace());
        request.setGroupName(keyOf(serviceName, groupName).group());
        request.setServiceName(serviceName);
        QueryServiceResponse response = connection.call(request, QueryServiceResponse.class);
        return filterHealthy(response.getServiceInfo(), healthyOnly);
    }

    /**
     * Pick one usable instance, weighted at random — the same rule Nacos applies
     * client-side, so no extra round trip is spent on it.
     */
    public Instance selectOneHealthyInstance(String serviceName) {
        return selectOneHealthyInstance(serviceName, config.defaultGroup());
    }

    public Instance selectOneHealthyInstance(String serviceName, String groupName) {
        List<Instance> candidates = getInstances(serviceName, groupName, true);
        if (candidates.isEmpty()) {
            throw new JawsServiceException("no healthy instance for service "
                    + keyOf(serviceName, groupName).toKeyString());
        }
        double totalWeight = candidates.stream().mapToDouble(Instance::getWeight).sum();
        if (totalWeight <= 0) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
        double cursor = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (Instance candidate : candidates) {
            cursor -= candidate.getWeight();
            if (cursor < 0) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    public List<String> listServices() {
        return listServices(config.defaultGroup());
    }

    public List<String> listServices(String groupName) {
        ServiceListRequest request = new ServiceListRequest();
        request.setNamespace(config.namespace());
        request.setGroupName(groupName);
        ServiceListResponse response = connection.call(request, ServiceListResponse.class);
        List<String> names = response.getServiceNames();
        return names != null ? names : List.of();
    }

    public boolean serverHealthy() {
        return connection.serverHealthy();
    }

    /**
     * The connection behind this client. Package-private rather than public:
     * recovery is driven by call failures and by the keep-alive, so callers have
     * nothing to do here — it exists so this package's tests can exercise the
     * reconnect path without a fake transport.
     */
    HarborConnection connection() {
        return connection;
    }

    // ========================================================================
    // Subscription
    // ========================================================================

    /**
     * Watch a service. The callback runs on the client's notifier thread, never
     * on the notification stream.
     * <p>
     * Keep the reference you pass in: a listener is removed by identity, and a
     * lambda or method reference is a fresh object each time it is written, so a
     * second {@code received::add} never matches the first and the service stays
     * subscribed.
     */
    public void subscribe(String serviceName, Consumer<ServiceInfo> listener) {
        subscribe(serviceName, config.defaultGroup(), listener);
    }

    public void subscribe(String serviceName, String groupName, Consumer<ServiceInfo> listener) {
        if (listener == null) {
            return;
        }
        ServiceKey key = keyOf(serviceName, groupName);
        ServiceSubscription subscription = subscriptions.computeIfAbsent(key,
                found -> new ServiceSubscription(found, ""));
        boolean first = !subscription.hasListeners();
        subscription.addListener(listener);
        if (!first) {
            // Already watched: hand over what we believe without another round trip.
            ServiceInfo cached = subscription.cached();
            if (cached != null) {
                notifier.execute(() -> listener.accept(cached));
            }
            return;
        }
        subscription.expectRegistered();
        SubscribeServiceResponse response = sendSubscribe(key, true);
        subscription.registered();
        subscription.cache(response.getServiceInfo());
        notifier.execute(() -> listener.accept(response.getServiceInfo()));
    }

    /**
     * Stop watching. Matches {@code listener} by identity, so this must be the
     * same object handed to {@link #subscribe(String, Consumer)}; an unmatched
     * listener leaves the subscription in place rather than guessing.
     */
    public void unsubscribe(String serviceName, Consumer<ServiceInfo> listener) {
        unsubscribe(serviceName, config.defaultGroup(), listener);
    }

    public void unsubscribe(String serviceName, String groupName, Consumer<ServiceInfo> listener) {
        ServiceKey key = keyOf(serviceName, groupName);
        ServiceSubscription subscription = subscriptions.get(key);
        if (subscription == null) {
            return;
        }
        if (!subscription.removeListener(listener)) {
            return;
        }
        // Same order as a deregister: owed until the server confirms it is gone,
        // so a lost reply leaves the removal for the next replay pass rather than
        // silently re-subscribing an unwatched service.
        subscription.expectUnregistered();
        sendSubscribe(key, false);
        subscription.unregistered();
        subscriptions.remove(key);
    }

    private SubscribeServiceResponse sendSubscribe(ServiceKey key, boolean subscribe) {
        SubscribeServiceRequest request = new SubscribeServiceRequest();
        request.setNamespace(key.namespace());
        request.setGroupName(key.group());
        request.setServiceName(key.name());
        request.setSubscribe(subscribe);
        request.setClusters("");
        return connection.call(request, SubscribeServiceResponse.class);
    }

    // ========================================================================
    // Push handling and recovery
    // ========================================================================

    private void onPushFrame(Payload payload) {
        NotifySubscriberRequest push =
                HarborProtocol.parseBody(payload, NotifySubscriberRequest.class);
        ServiceKey key = keyOf(push.getServiceName(), push.getGroupName());
        ServiceSubscription subscription = subscriptions.get(key);
        if (subscription == null) {
            // A push for a service we stopped watching; state is re-read on the
            // next subscribe, so there is nothing to reconcile here.
            log.debug("[harbor-client] dropping push for unwatched service {}", key);
            return;
        }
        ServiceInfo latest = push.getServiceInfo();
        subscription.cache(latest);
        List<Consumer<ServiceInfo>> listeners = new ArrayList<>(subscription.listeners());
        notifier.execute(() -> listeners.forEach(each -> each.accept(latest)));
    }

    /**
     * Re-establish what the dead connection owned. Harbor keys both instances
     * and subscribers to a per-connection id, so anything not replayed here is
     * simply gone from the registry.
     */
    private void replayOwnedState() {
        // The session that just died held all of these; without dirtying the
        // confirmations first, every entry reads RedoType.NONE and the replay is
        // a no-op. This is what Nacos's onDisConnect does to both of its tables.
        registrations.values().forEach(RedoData::markDirty);
        subscriptions.values().forEach(RedoData::markDirty);

        int replayed = 0;
        for (Map.Entry<ServiceKey, InstanceRedoData> each : registrations.entrySet()) {
            replayed += replayRegistration(each.getKey(), each.getValue()) ? 1 : 0;
        }
        for (Map.Entry<ServiceKey, ServiceSubscription> each : subscriptions.entrySet()) {
            replayed += replaySubscription(each.getKey(), each.getValue()) ? 1 : 0;
        }
        log.info("[harbor-client] replayed {} owned state change(s), {} registration(s) and"
                        + " {} subscription(s) remain",
                replayed, registrations.size(), subscriptions.size());
    }

    /**
     * @return true when a request actually went out
     */
    private boolean replayRegistration(ServiceKey key, InstanceRedoData entry) {
        switch (entry.getRedoType()) {
            case REGISTER -> {
                try {
                    sendInstanceRequest(key, entry.instance(), HarborProtocol.REGISTER_INSTANCE);
                    entry.registered();
                } catch (Exception e) {
                    log.warn("[harbor-client] replay of registration {} failed: {}",
                            key, e.getMessage());
                    return false;
                }
                return true;
            }
            case UNREGISTER -> {
                try {
                    sendInstanceRequest(key, entry.instance(),
                            HarborProtocol.DEREGISTER_INSTANCE);
                    entry.unregistered();
                } catch (Exception e) {
                    log.warn("[harbor-client] replay of pending deregister {} failed: {}",
                            key, e.getMessage());
                    return false;
                }
                registrations.remove(key);
                return true;
            }
            case REMOVE -> {
                registrations.remove(key);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean replaySubscription(ServiceKey key, ServiceSubscription entry) {
        switch (entry.getRedoType()) {
            case REGISTER -> {
                try {
                    SubscribeServiceResponse response = sendSubscribe(key, true);
                    entry.registered();
                    entry.cache(response.getServiceInfo());
                } catch (Exception e) {
                    log.warn("[harbor-client] replay of subscription {} failed: {}",
                            key, e.getMessage());
                    return false;
                }
                return true;
            }
            case UNREGISTER -> {
                try {
                    sendSubscribe(key, false);
                    entry.unregistered();
                } catch (Exception e) {
                    log.warn("[harbor-client] replay of pending unsubscribe {} failed: {}",
                            key, e.getMessage());
                    return false;
                }
                subscriptions.remove(key);
                return true;
            }
            case REMOVE -> {
                subscriptions.remove(key);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private ServiceKey keyOf(String serviceName, String groupName) {
        return ServiceKey.of(config.namespace(),
                groupName == null || groupName.isEmpty() ? config.defaultGroup() : groupName,
                serviceName);
    }

    private static List<Instance> filterHealthy(ServiceInfo serviceInfo, boolean healthyOnly) {
        if (serviceInfo == null || serviceInfo.getHosts() == null) {
            return List.of();
        }
        if (!healthyOnly) {
            return serviceInfo.getHosts();
        }
        List<Instance> usable = new ArrayList<>();
        for (Instance each : serviceInfo.getHosts()) {
            if (each.isHealthy() && each.isEnabled()) {
                usable.add(each);
            }
        }
        return usable;
    }

    /**
     * Close the connection and stop the notifier.
     * <p>
     * No deregistration is sent: ending the stream runs harbor's closure
     * transaction, which removes this connection's instances and subscribers —
     * the same transaction a dropped channel triggers.
     */
    @Override
    public void close() {
        connection.close();
        notifier.shutdownNow();
        registrations.clear();
        subscriptions.clear();
    }
}
