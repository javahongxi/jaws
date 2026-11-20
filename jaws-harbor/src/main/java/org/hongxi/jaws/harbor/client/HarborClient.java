package org.hongxi.jaws.harbor.client;

import org.hongxi.jaws.common.VisibleForTesting;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.harbor.HarborProtocol;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.model.ServiceKey;
import org.hongxi.jaws.harbor.model.request.BatchInstanceRequest;
import org.hongxi.jaws.harbor.model.request.ConfigBatchListenRequest;
import org.hongxi.jaws.harbor.model.request.ConfigChangeNotifyRequest;
import org.hongxi.jaws.harbor.model.request.ConfigPublishRequest;
import org.hongxi.jaws.harbor.model.request.ConfigQueryRequest;
import org.hongxi.jaws.harbor.model.request.ConfigRemoveRequest;
import org.hongxi.jaws.harbor.model.request.InstanceRequest;
import org.hongxi.jaws.harbor.model.request.NotifySubscriberRequest;
import org.hongxi.jaws.harbor.model.request.PersistentInstanceRequest;
import org.hongxi.jaws.harbor.model.request.ServiceListRequest;
import org.hongxi.jaws.harbor.model.request.ServiceQueryRequest;
import org.hongxi.jaws.harbor.model.request.SubscribeServiceRequest;
import org.hongxi.jaws.harbor.model.response.BatchInstanceResponse;
import org.hongxi.jaws.harbor.model.response.ConfigChangeBatchListenResponse;
import org.hongxi.jaws.harbor.model.response.ConfigPublishResponse;
import org.hongxi.jaws.harbor.model.response.ConfigQueryResponse;
import org.hongxi.jaws.harbor.model.response.ConfigRemoveResponse;
import org.hongxi.jaws.harbor.model.response.InstanceResponse;
import org.hongxi.jaws.harbor.model.response.QueryServiceResponse;
import org.hongxi.jaws.harbor.model.response.ServiceListResponse;
import org.hongxi.jaws.harbor.model.response.SubscribeServiceResponse;
import org.hongxi.jaws.harbor.proto.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Native client of a harbor registry and config server, speaking the same wire
 * protocol the server answers for nacos-client.
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
    private final HarborConnection connection;
    private final Map<ServiceKey, InstanceRedoData> registrations = new ConcurrentHashMap<>();
    private final Map<ServiceKey, ServiceSubscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<ConfigKey, ConfigListenData> configListens = new ConcurrentHashMap<>();
    private final ExecutorService notifier;
    private final ScheduledExecutorService redoScheduler;

    public HarborClient(String host, int port) {
        this(HarborClientConfig.of(host, port));
    }

    public HarborClient(HarborClientConfig config) {
        this.config = config;
        this.connection = new HarborConnection(config, this::onPushFrame,
                this::onConfigPush, this::replayOwnedState);
        this.connection.start();
        this.notifier = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "harbor-client-notifier");
            thread.setDaemon(true);
            return thread;
        });
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
    @VisibleForTesting
    int registrationCount() {
        return registrations.size();
    }

    @VisibleForTesting
    int subscriptionCount() {
        return subscriptions.size();
    }

    @VisibleForTesting
    int configListenCount() {
        return configListens.size();
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
        // Left in place on purpose: it now reads RedoType.REMOVE and the redo pass
        // collects it, exactly as Nacos defers collection to its RedoScheduledTask.
    }

    /**
     * The ephemeral/persistent fork, mirroring Nacos's {@code NamingClientProxyDelegate}:
     * a persistent instance rides its own request type into the CP log, an ephemeral
     * one rides the connection-bound path. The redo replay funnels through here too,
     * so a reconnect re-registers either kind with the right verb — and a persistent
     * re-registration is an idempotent replace, not a duplicate.
     */
    private void sendInstanceRequest(ServiceKey key, Instance instance, String operation) {
        if (instance.isEphemeral()) {
            InstanceRequest request = new InstanceRequest();
            request.setNamespace(key.namespace());
            request.setGroupName(key.group());
            request.setServiceName(key.name());
            request.setType(operation);
            request.setInstance(instance);
            connection.call(request, InstanceResponse.class);
        } else {
            PersistentInstanceRequest request = new PersistentInstanceRequest();
            request.setNamespace(key.namespace());
            request.setGroupName(key.group());
            request.setServiceName(key.name());
            request.setType(operation);
            request.setInstance(instance);
            connection.call(request, InstanceResponse.class);
        }
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
        sendBatchRegisterRequest(key, instances);
        entry.registered();
    }

    private void sendBatchRegisterRequest(ServiceKey key, List<Instance> instances) {
        BatchInstanceRequest request = new BatchInstanceRequest();
        request.setNamespace(key.namespace());
        request.setGroupName(key.group());
        request.setServiceName(key.name());
        request.setType(HarborProtocol.BATCH_REGISTER_INSTANCE);
        request.setInstances(instances);
        connection.call(request, BatchInstanceResponse.class);
    }

    /**
     * Drop several instances of one service, expressed the way Nacos expresses it:
     * as a batch re-registration of the instances that survive.
     * <p>
     * Harbor's {@code batchRegisterInstance} is a replace — the batch is the whole
     * set this connection owns for the service, exactly like Nacos — so a batch has
     * no "remove" verb of its own. We take the redo entry's current instances, drop
     * the addressed ones, and send the remainder back as one batch request. The redo
     * is rewritten to the remainder <em>before</em> the request goes out, so a failed
     * send leaves the removal owed and the next replay converges instead of
     * resurrecting what we dropped. An empty remainder is still sent: it is how a
     * full batch deregistration clears the service.
     * <p>
     * As in Nacos, a batch may only be shrunk from another batch — a service first
     * registered instance-by-instance has no {@link BatchInstanceRedoData} to retain
     * from, and is rejected rather than silently converted.
     */
    public void batchDeregisterInstance(String serviceName, List<Instance> instances) {
        batchDeregisterInstance(serviceName, config.defaultGroup(), instances);
    }

    public void batchDeregisterInstance(String serviceName, String groupName,
                                        List<Instance> instances) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("no instances to deregister for " + serviceName);
        }
        ServiceKey key = keyOf(serviceName, groupName);
        Set<String> removals = new HashSet<>();
        for (Instance each : instances) {
            requireEphemeral(key, each);
            removals.add(addressKey(each));
        }
        InstanceRedoData entry = registrations.get(key);
        if (entry == null) {
            return;
        }
        if (!(entry instanceof BatchInstanceRedoData batch)) {
            throw new IllegalArgumentException(
                    "batch deregister requires a batch registration: " + serviceName);
        }
        List<Instance> retain = new ArrayList<>();
        for (Instance held : batch.instances()) {
            if (!removals.contains(addressKey(held))) {
                retain.add(held);
            }
        }
        // Local truth becomes the remainder first (Nacos does the same under its redo
        // lock): the replay reads this entry, so it must already exclude the removed
        // set before the request leaves, or a lost reply re-registers what we dropped.
        BatchInstanceRedoData shrunk = new BatchInstanceRedoData(retain);
        registrations.put(key, shrunk);
        sendBatchRegisterRequest(key, retain);
        shrunk.registered();
    }

    /** Address + port identity, matching {@code ServiceStorage} deregistration and Nacos {@code getRetainInstance}. */
    private static String addressKey(Instance instance) {
        return instance.getIp() + "#" + instance.getPort();
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
        boolean first = subscription.listeners().isEmpty();
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
        subscription.removeListener(listener);
        if (subscription.listeners().isEmpty()) {
            // Same order as a deregister: owed until the server confirms it is gone,
            // so a lost reply leaves the removal for the next replay pass rather than
            // silently re-subscribing an unwatched service.
            subscription.expectUnregistered();
            sendSubscribe(key, subscription.clusters(), false);
            subscription.unregistered();
            // Collected by the redo pass, not here.
        }
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
    // Config center
    // ========================================================================

    /**
     * Read one config; {@code null} when the key does not exist — the server's
     * {@code CONFIG_NOT_FOUND} is a state, not a failure.
     */
    public String getConfig(String dataId) {
        return getConfig(dataId, config.defaultGroup());
    }

    public String getConfig(String dataId, String group) {
        return getConfig(dataId, group, config.namespace());
    }

    public String getConfig(String dataId, String group, String tenant) {
        ConfigQueryResponse response = queryConfig(configKeyOf(dataId, group, tenant));
        return response == null ? null : response.getContent();
    }

    /**
     * Publish one config. The write rides the server's raft log, so a successful
     * reply means the change is committed and every node applies it —
     * last-write-wins, no CAS.
     */
    public void publishConfig(String dataId, String content) {
        publishConfig(dataId, config.defaultGroup(), content);
    }

    public void publishConfig(String dataId, String group, String content) {
        publishConfig(dataId, group, config.namespace(), content);
    }

    public void publishConfig(String dataId, String group, String tenant, String content) {
        ConfigKey key = configKeyOf(dataId, group, tenant);
        ConfigPublishRequest request = new ConfigPublishRequest(
                key.dataId(), key.group(), key.tenant(), content == null ? "" : content);
        connection.call(request, ConfigPublishResponse.class);
    }

    /** Remove one config through the same raft path; a missing key still succeeds. */
    public void removeConfig(String dataId) {
        removeConfig(dataId, config.defaultGroup());
    }

    public void removeConfig(String dataId, String group) {
        removeConfig(dataId, group, config.namespace());
    }

    public void removeConfig(String dataId, String group, String tenant) {
        ConfigKey key = configKeyOf(dataId, group, tenant);
        connection.call(new ConfigRemoveRequest(key.dataId(), key.group(), key.tenant()),
                ConfigRemoveResponse.class);
    }

    /**
     * Watch one config. The callback runs on the notifier thread and receives
     * the current content immediately, then the new content on every change —
     * and {@code null} when the config is removed. As with {@link #subscribe},
     * a listener is removed by identity: keep the reference you pass in.
     */
    public void addConfigListener(String dataId, Consumer<String> listener) {
        addConfigListener(dataId, config.defaultGroup(), listener);
    }

    public void addConfigListener(String dataId, String group, Consumer<String> listener) {
        addConfigListener(dataId, group, config.namespace(), listener);
    }

    public void addConfigListener(String dataId, String group, String tenant,
                                  Consumer<String> listener) {
        if (listener == null) {
            return;
        }
        ConfigKey key = configKeyOf(dataId, group, tenant);
        ConfigListenData entry = configListens.computeIfAbsent(key, ConfigListenData::new);
        boolean first = entry.listeners().isEmpty();
        entry.addListener(listener);
        if (!first) {
            // Already watched: hand over what we believe without another round trip.
            notifier.execute(() -> listener.accept(entry.content()));
            return;
        }
        entry.expectRegistered();
        // Pull first, then listen with the md5 we hold: the server only reports
        // keys that moved past that md5, so the initial value is never notified
        // twice.
        ConfigQueryResponse current = queryConfig(key);
        entry.cache(current == null ? null : current.getContent(),
                current == null ? "" : current.getMd5());
        ConfigChangeBatchListenResponse response = sendListen(key, entry.md5(), true);
        entry.registered();
        notifyListeners(entry, entry.content());
        pullChanged(key, entry, response);
    }

    /**
     * Stop watching one config, matching the listener by identity. The last
     * listener out sends the unlisten; a lost reply leaves the removal owed to
     * the redo pass, exactly as an unsubscribe does.
     */
    public void removeConfigListener(String dataId, Consumer<String> listener) {
        removeConfigListener(dataId, config.defaultGroup(), listener);
    }

    public void removeConfigListener(String dataId, String group, Consumer<String> listener) {
        removeConfigListener(dataId, group, config.namespace(), listener);
    }

    public void removeConfigListener(String dataId, String group, String tenant,
                                     Consumer<String> listener) {
        ConfigKey key = configKeyOf(dataId, group, tenant);
        ConfigListenData entry = configListens.get(key);
        if (entry == null) {
            return;
        }
        entry.removeListener(listener);
        if (entry.listeners().isEmpty()) {
            entry.expectUnregistered();
            sendListen(key, entry.md5(), false);
            entry.unregistered();
            // Collected by the redo pass, not here.
        }
    }

    private ConfigQueryResponse queryConfig(ConfigKey key) {
        ConfigQueryRequest request = new ConfigQueryRequest();
        request.setDataId(key.dataId());
        request.setGroup(key.group());
        request.setTenant(key.tenant());
        // tryCall, not call: CONFIG_NOT_FOUND is a legitimate answer ("gone"),
        // not a rejection to throw on.
        ConfigQueryResponse response = connection.tryCall(request, ConfigQueryResponse.class);
        if (response.getResultCode() == ConfigQueryResponse.CONFIG_NOT_FOUND) {
            return null;
        }
        if (!response.isSuccess()) {
            throw new JawsServiceException("harbor rejected ConfigQueryRequest: resultCode="
                    + response.getResultCode());
        }
        return response;
    }

    private ConfigChangeBatchListenResponse sendListen(ConfigKey key, String md5, boolean listen) {
        ConfigBatchListenRequest request = new ConfigBatchListenRequest();
        request.setListen(listen);
        request.addConfigListenContext(key.group(), key.dataId(), key.tenant(), md5);
        return connection.call(request, ConfigChangeBatchListenResponse.class);
    }

    /** Query the key a listen reply flagged as changed and notify if it moved. */
    private void pullChanged(ConfigKey key, ConfigListenData entry,
                             ConfigChangeBatchListenResponse response) {
        if (response.getChangedConfigs() != null && !response.getChangedConfigs().isEmpty()) {
            refreshConfig(key, entry);
        }
    }

    /**
     * Re-read one watched config and notify its listeners only when the content
     * actually moved (md5 comparison, the nacos-client rule) — a removal reads
     * as null content and notifies once.
     */
    private void refreshConfig(ConfigKey key, ConfigListenData entry) {
        try {
            ConfigQueryResponse latest = queryConfig(key);
            String content = latest == null ? null : latest.getContent();
            String md5 = latest == null || latest.getMd5() == null ? "" : latest.getMd5();
            if (md5.equals(entry.md5())) {
                return;
            }
            entry.cache(content, md5);
            notifyListeners(entry, content);
        } catch (Exception e) {
            log.warn("[harbor-client] refresh of config {} failed: {}", key, e.getMessage());
        }
    }

    private void notifyListeners(ConfigListenData entry, String content) {
        List<Consumer<String>> listeners = new ArrayList<>(entry.listeners());
        notifier.execute(() -> listeners.forEach(each -> each.accept(content)));
    }

    private ConfigKey configKeyOf(String dataId, String group, String tenant) {
        return ConfigKey.of(dataId,
                group == null || group.isEmpty() ? config.defaultGroup() : group,
                tenant == null || tenant.isEmpty() ? config.namespace() : tenant);
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
     * A config-change notice arrives on the bi-stream carrying only the key
     * (nacos {@code ConfigChangeNotifyRequest}); the new content is pulled with
     * a query on the notifier thread, so a slow listener never stalls the
     * stream and a dropped notice is healed by the next listen replay.
     */
    private void onConfigPush(Payload payload) {
        ConfigChangeNotifyRequest notice =
                HarborProtocol.parseBody(payload, ConfigChangeNotifyRequest.class);
        ConfigKey key = ConfigKey.of(notice.getDataId(), notice.getGroup(), notice.getTenant());
        ConfigListenData entry = configListens.get(key);
        if (entry == null || entry.listeners().isEmpty()) {
            log.debug("[harbor-client] dropping config notice for unwatched key {}", key);
            return;
        }
        notifier.execute(() -> refreshConfig(key, entry));
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
        // a no-op. This is what Nacos's onDisConnect does to its redo tables.
        registrations.values().forEach(RedoData::markDirty);
        subscriptions.values().forEach(RedoData::markDirty);
        configListens.values().forEach(RedoData::markDirty);
        reconcileOwnedState();
    }

    /**
     * Walk the redo tables and do what each entry owes: re-register, complete a
     * removal that was never confirmed, or collect a spent one. Nothing is sent
     * for an entry that the server already agrees with, so an idle client stays
     * idle.
     */
    private void reconcileOwnedState() {
        int replayed = 0;
        for (Map.Entry<ServiceKey, InstanceRedoData> each : registrations.entrySet()) {
            replayed += replayRegistration(each.getKey(), each.getValue()) ? 1 : 0;
        }
        for (Map.Entry<ServiceKey, ServiceSubscription> each : subscriptions.entrySet()) {
            replayed += replaySubscription(each.getKey(), each.getValue()) ? 1 : 0;
        }
        for (Map.Entry<ConfigKey, ConfigListenData> each : configListens.entrySet()) {
            replayed += replayConfigListen(each.getKey(), each.getValue()) ? 1 : 0;
        }
        if (replayed > 0) {
            log.info("[harbor-client] reconciled {} state change(s); {} registration(s),"
                    + " {} subscription(s) and {} config listen(s) remain",
                    replayed, registrations.size(), subscriptions.size(), configListens.size());
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
            sendBatchRegisterRequest(key, batch.instances());
            return;
        }
        sendInstanceRequest(key, entry.instance(), HarborProtocol.REGISTER_INSTANCE);
    }

    /**
     * Complete an owed removal. A batch is taken down the same way the live API
     * shrinks one — a batch register of the empty remainder. A batch is a whole-set
     * replace, so the empty set clears every instance this connection holds for the
     * service in a single round trip (and a single peer sync), instead of a
     * per-instance deregister each. Only a single-instance entry uses the
     * instance-level verb.
     */
    private void deregisterEntry(ServiceKey key, InstanceRedoData entry) {
        if (entry instanceof BatchInstanceRedoData) {
            sendBatchRegisterRequest(key, List.of());
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

    /**
     * Re-arm one config listen on the fresh connection, carrying the md5 we
     * still hold: the reply names whatever moved while we were away, and only
     * those keys are pulled and notified — the reconnect itself stays silent
     * to listeners when nothing changed.
     */
    private boolean replayConfigListen(ConfigKey key, ConfigListenData entry) {
        switch (entry.getRedoType()) {
            case REGISTER -> {
                try {
                    ConfigChangeBatchListenResponse response = sendListen(key, entry.md5(), true);
                    entry.registered();
                    pullChanged(key, entry, response);
                } catch (Exception e) {
                    log.warn("[harbor-client] replay of config listen {} failed: {}",
                            key, e.getMessage());
                    return false;
                }
                return true;
            }
            case UNREGISTER -> {
                try {
                    sendListen(key, entry.md5(), false);
                    entry.unregistered();
                } catch (Exception e) {
                    log.warn("[harbor-client] replay of pending config unlisten {} failed: {}",
                            key, e.getMessage());
                    return false;
                }
                configListens.remove(key);
                return true;
            }
            case REMOVE -> {
                configListens.remove(key);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** Batches stay ephemeral-only: the CP path has no batch verb, one request per instance. */
    private static void requireEphemeral(ServiceKey key, Instance instance) {
        if (!instance.isEphemeral()) {
            throw new IllegalArgumentException(key.toKeyString()
                    + " asks ephemeral=false in a batch; use registerInstance per instance "
                    + "(persistent instances replicate via Raft)");
        }
    }

    private ServiceKey keyOf(String serviceName, String groupName) {
        return ServiceKey.of(config.namespace(),
                groupName == null || groupName.isEmpty() ? config.defaultGroup() : groupName,
                serviceName);
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
    @VisibleForTesting
    HarborConnection connection() {
        return connection;
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
        configListens.clear();
    }
}
