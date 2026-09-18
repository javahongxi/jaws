package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.harbor.model.request.BatchInstanceRequest;
import org.hongxi.jaws.harbor.model.request.InstanceRequest;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.model.request.ServiceListRequest;
import org.hongxi.jaws.harbor.model.request.ServiceQueryRequest;
import org.hongxi.jaws.harbor.model.request.SubscribeServiceRequest;
import org.hongxi.jaws.harbor.model.response.BatchInstanceResponse;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ScheduledExecutorService redoScheduler;
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
        this.redoScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "harbor-client-redo");
            thread.setDaemon(true);
            return thread;
        });
        // Nacos drives the same reconcile from a fixed-delay task: an owed removal
        // must complete without waiting for a reconnect, and spent entries must be
        // collected rather than accumulate.
        this.redoScheduler.scheduleWithFixedDelay(this::reconcileQuietly,
                config.redoDelayMillis(), config.redoDelayMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Entries still held in the two redo tables, including spent ones awaiting
     * collection. Exposed for this package's tests: deferred removal is only safe if
     * the pass really collects.
     */
    int registrationCount() {
        return registrations.size();
    }

    int subscriptionCount() {
        return subscriptions.size();
    }

    // ========================================================================
    // Registration
    // ========================================================================

    public void registerInstance(String serviceName, Instance instance) {
        registerInstance(serviceName, config.defaultGroup(), instance);
    }

    public void registerInstance(String serviceName, String groupName, Instance instance) {
        ServiceKey key = keyOf(serviceName, groupName);
        requireEphemeral(key, instance);
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
        requireEphemeral(key, instance);
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
        // Left in place on purpose: it now reads RedoType.REMOVE and the redo pass
        // collects it, exactly as Nacos defers collection to its RedoScheduledTask.
    }

    /**
     * Register several instances of one service in a single request, as Nacos's
     * {@code batchRegisterInstance} does.
     * <p>
     * The entry this leaves in the redo table replaces any single-instance entry for
     * the same service: a service is owed at most one registration shape, and the
     * replay has to know which one to send.
     */
    public void batchRegisterInstance(String serviceName, List<Instance> instances) {
        batchRegisterInstance(serviceName, config.defaultGroup(), instances);
    }

    public void batchRegisterInstance(String serviceName, String groupName,
                                      List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("no instances to register for " + serviceName);
        }
        ServiceKey key = keyOf(serviceName, groupName);
        for (Instance each : instances) {
            requireEphemeral(key, each);
        }
        BatchInstanceRedoData entry = new BatchInstanceRedoData(instances);
        registrations.put(key, entry);
        sendBatchRequest(key, instances, HarborProtocol.BATCH_REGISTER_INSTANCE);
        entry.registered();
    }

    private void sendBatchRequest(ServiceKey key, List<Instance> instances, String operation) {
        BatchInstanceRequest request = new BatchInstanceRequest();
        request.setNamespace(key.namespace());
        request.setGroupName(key.group());
        request.setServiceName(key.name());
        request.setType(operation);
        request.setInstances(instances);
        connection.call(request, BatchInstanceResponse.class);
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
        return getInstances(serviceName, config.defaultGroup(), null, false);
    }

    public List<Instance> getInstances(String serviceName, boolean healthyOnly) {
        return getInstances(serviceName, config.defaultGroup(), null, healthyOnly);
    }

    public List<Instance> getInstances(String serviceName, String groupName,
                                       boolean healthyOnly) {
        return getInstances(serviceName, groupName, null, healthyOnly);
    }

    /**
     * @param cluster      comma-separated cluster allow-list, null for every cluster
     * @param healthyOnly  keep only healthy instances
     */
    public List<Instance> getInstances(String serviceName, String groupName, String cluster,
                                       boolean healthyOnly) {
        ServiceKey key = keyOf(serviceName, groupName);
        ServiceQueryRequest request = new ServiceQueryRequest();
        request.setNamespace(key.namespace());
        request.setGroupName(key.group());
        request.setServiceName(serviceName);
        request.setCluster(cluster == null ? "" : cluster);
        request.setHealthyOnly(healthyOnly);
        // The server answers the projection, so nothing is filtered a second time
        // here: a local retry would only hide a filter that failed to apply.
        QueryServiceResponse response = connection.call(request, QueryServiceResponse.class);
        ServiceInfo serviceInfo = response.getServiceInfo();
        if (serviceInfo == null || serviceInfo.getHosts() == null) {
            return List.of();
        }
        return serviceInfo.getHosts();
    }

    /**
     * Pick one usable instance, weighted at random — the same rule Nacos applies
     * client-side, so no extra round trip is spent on it.
     */
    public Instance selectOneHealthyInstance(String serviceName) {
        return selectOneHealthyInstance(serviceName, config.defaultGroup());
    }

    public Instance selectOneHealthyInstance(String serviceName, String groupName) {
        List<Instance> candidates = getInstances(serviceName, groupName, null, true);
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
        return listServicesPage(groupName, 1, Integer.MAX_VALUE).names();
    }

    /**
     * One page of service names. Pages are 1-based and {@code total} is the whole
     * match set, not the page size — Nacos semantics, so a caller can page without
     * losing the count.
     */
    public ServicePage listServicesPage(String groupName, int pageNo, int pageSize) {
        ServiceListRequest request = new ServiceListRequest();
        request.setNamespace(config.namespace());
        request.setGroupName(groupName);
        request.setPageNo(pageNo);
        request.setPageSize(pageSize);
        ServiceListResponse response = connection.call(request, ServiceListResponse.class);
        List<String> names = response.getServiceNames();
        return new ServicePage(names != null ? names : List.of(), response.getCount(),
                pageNo, pageSize);
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
        subscribe(serviceName, config.defaultGroup(), "", listener);
    }

    public void subscribe(String serviceName, String groupName, Consumer<ServiceInfo> listener) {
        subscribe(serviceName, groupName, "", listener);
    }

    /**
     * @param clusters comma-separated cluster allow-list; pushes are then narrowed
     *                 per subscriber, and the subscriber only learns of changes to
     *                 those clusters
     */
    public void subscribe(String serviceName, String groupName, String clusters,
                          Consumer<ServiceInfo> listener) {
        if (listener == null) {
            return;
        }
        ServiceKey key = keyOf(serviceName, groupName);
        ServiceSubscription subscription = subscriptions.computeIfAbsent(key,
                found -> new ServiceSubscription(found, clusters));
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
        SubscribeServiceResponse response = sendSubscribe(key, subscription.clusters(), true);
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
        sendSubscribe(key, subscription.clusters(), false);
        subscription.unregistered();
        // Collected by the redo pass, not here.
    }

    private SubscribeServiceResponse sendSubscribe(ServiceKey key, String clusters,
                                                   boolean subscribe) {
        SubscribeServiceRequest request = new SubscribeServiceRequest();
        request.setNamespace(key.namespace());
        request.setGroupName(key.group());
        request.setServiceName(key.name());
        request.setSubscribe(subscribe);
        request.setClusters(clusters == null ? "" : clusters);
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
     * One reconcile tick. A dead connection is skipped — the recovery path replays
     * everything anyway, and queueing calls against a broken stream only turns the
     * log into noise.
     */
    private void reconcileQuietly() {
        try {
            if (!connection.isConnected()) {
                return;
            }
            reconcileOwnedState();
        } catch (Exception e) {
            log.warn("[harbor-client] reconcile pass failed: {}", e.getMessage());
        }
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
        reconcileOwnedState();
    }

    /**
     * Walk both tables and do what each entry owes: re-register, complete a removal
     * that was never confirmed, or collect a spent one. Nothing is sent for an entry
     * that the server already agrees with, so an idle client stays idle.
     */
    private void reconcileOwnedState() {
        int replayed = 0;
        for (Map.Entry<ServiceKey, InstanceRedoData> each : registrations.entrySet()) {
            replayed += replayRegistration(each.getKey(), each.getValue()) ? 1 : 0;
        }
        for (Map.Entry<ServiceKey, ServiceSubscription> each : subscriptions.entrySet()) {
            replayed += replaySubscription(each.getKey(), each.getValue()) ? 1 : 0;
        }
        if (replayed > 0) {
            log.info("[harbor-client] reconciled {} state change(s); {} registration(s) and"
                    + " {} subscription(s) remain",
                    replayed, registrations.size(), subscriptions.size());
        }
    }

    /**
     * @return true when a request actually went out
     */
    private boolean replayRegistration(ServiceKey key, InstanceRedoData entry) {
        switch (entry.getRedoType()) {
            case REGISTER -> {
                try {
                    sendRegistration(key, entry);
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
                    deregisterEntry(key, entry);
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

    /**
     * Send a owed registration in the shape it was made in — one request for a
     * single instance, one batch request for a batch (Nacos branches the same way in
     * {@code RedoScheduledTask.processRegisterRedoType}).
     */
    private void sendRegistration(ServiceKey key, InstanceRedoData entry) {
        if (entry instanceof BatchInstanceRedoData batch) {
            sendBatchRequest(key, batch.instances(), HarborProtocol.BATCH_REGISTER_INSTANCE);
            return;
        }
        sendInstanceRequest(key, entry.instance(), HarborProtocol.REGISTER_INSTANCE);
    }

    /**
     * Complete an owed removal. A batch entry is taken down instance by instance:
     * Nacos has no batch-deregister action constant, so its redo pass would hand a
     * null instance to the single path here — we do the obvious thing instead.
     */
    private void deregisterEntry(ServiceKey key, InstanceRedoData entry) {
        if (entry instanceof BatchInstanceRedoData batch) {
            for (Instance each : batch.instances()) {
                sendInstanceRequest(key, each, HarborProtocol.DEREGISTER_INSTANCE);
            }
            return;
        }
        sendInstanceRequest(key, entry.instance(), HarborProtocol.DEREGISTER_INSTANCE);
    }

    private boolean replaySubscription(ServiceKey key, ServiceSubscription entry) {
        switch (entry.getRedoType()) {
            case REGISTER -> {
                try {
                    SubscribeServiceResponse response =
                            sendSubscribe(key, entry.clusters(), true);
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
                    sendSubscribe(key, entry.clusters(), false);
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

    private static void requireEphemeral(ServiceKey key, Instance instance) {
        if (!instance.isEphemeral()) {
            throw new IllegalArgumentException(key.toKeyString()
                    + " asks ephemeral=false; " + HarborProtocol.UNSUPPORTED_BOUNDARY);
        }
    }

    private ServiceKey keyOf(String serviceName, String groupName) {
        return ServiceKey.of(config.namespace(),
                groupName == null || groupName.isEmpty() ? config.defaultGroup() : groupName,
                serviceName);
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
        redoScheduler.shutdownNow();
        connection.close();
        notifier.shutdownNow();
        registrations.clear();
        subscriptions.clear();
    }
}
