package org.hongxi.jaws.store.client;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.store.BrokerInfo;
import org.hongxi.jaws.store.MessageView;
import org.hongxi.jaws.store.OffsetResult;
import org.hongxi.jaws.store.PullResult;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin push-style consumer built on a self-driving pull pump: each pull's
 * callback re-arms the next pull (pullInterval=0), so consumption speed
 * adaptively catches up with production speed and long polling keeps idle
 * cost near zero.
 *
 * <p>Multi-master mode: one connection and one pump per (broker, queue).
 * Brokers come either from a comma-separated directUrl list or from registry
 * discovery (refreshed every 5s); each broker reports its stable brokerName
 * via brokerInfoAsync and keeps its own group@topic@queueId progress, so the
 * same queue name on different brokers never shares an offset. A broker going
 * away stops its pumps; its offsets stay on that broker for its return.
 *
 * <p>Progress lives on the broker, not here. On attach the consumer reads the
 * group's stored offset from that broker and treats it as authoritative; every
 * successful batch commits it back with increaseOnly. Nothing is persisted
 * locally: {@code subscription.pullOffset} is the only in-process copy, so a
 * restart, a rebuilt container or a fresh process all start from the same
 * server-side position. RocketMQ's client does keep an offset table in memory
 * because its commits are batched every 5s; this consumer commits per batch, so
 * a local copy would only be a second answer to "where is this group".
 *
 * <p>A queue the group has never committed to starts at the broker's tail
 * (maxOffsetAsync), the way RocketMQ's CONSUME_FROM_LAST_OFFSET skips a new
 * group's backlog; there is no first-offset or timestamp reset here. An offset
 * that cannot be read leaves the broker unattached — retried on a later discovery
 * round — rather than starting from a guessed position.
 *
 * <p>Flow control: when dispatched-but-unconsumed messages reach
 * pullThresholdForQueue the pump pauses briefly — the consumer's own
 * backpressure gate, so broker speed never floods the listener.
 *
 * <p>Deliberately absent vs RocketMQ consumer: rebalance (every instance
 * consumes every queue of every broker; run one instance per group),
 * ProcessQueue sliding-window reorder, retry/DLQ.
 */
public class StoreConsumer {

    private static final Logger logger = LoggerFactory.getLogger(StoreConsumer.class);

    /** Interval between registry discovery refreshes. */
    private static final long DISCOVERY_REFRESH_INTERVAL_SECONDS = 5;

    /**
     * Hold requested from the broker on an empty pull, mirroring RocketMQ's
     * BROKER_SUSPEND_MAX_TIME_MILLIS (15s). RocketMQ lets the client pick this;
     * the broker honors the requested value as-is.
     */
    private static final long BROKER_SUSPEND_MAX_TIME_MILLIS = 15_000;

    /**
     * RPC budget for a held pull, mirroring RocketMQ's
     * CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND (30s, exactly 2x the hold).
     */
    private static final long CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND = 30_000;

    /**
     * Round gap used only in short-polling mode (suspendTimeoutMillis <= 0) after an
     * empty pull. Mirrors RocketMQ's PULL_TIME_DELAY_MILLS_WHEN_SUSPEND (1s): the next
     * pump is scheduled on the pump executor, never a blocking Thread.sleep.
     */
    private static final long SHORT_POLLING_INTERVAL_MILLIS = 1_000;

    /**
     * Budget for each offset query issued before a broker's pumps start. A round
     * goes out concurrently, so this bounds one round trip rather than one per
     * queue; on expiry the broker is left unattached instead of starting from a
     * guessed position.
     */
    private static final long OFFSET_QUERY_TIMEOUT_MILLIS = 3_000;

    private final String directUrls;
    private final List<RegistryConfig> registries;
    private final String serialization;
    private final String consumerGroup;
    private final Map<String, Template> templates = new ConcurrentHashMap<>();
    private final Map<String, BrokerConnection> brokers = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    private int pullBatchSize = 32;
    private int pullThresholdForQueue = 1000;
    private long suspendTimeoutMillis = BROKER_SUSPEND_MAX_TIME_MILLIS;
    private long shortPollingIntervalMillis = SHORT_POLLING_INTERVAL_MILLIS;

    private ReferenceConfig<StoreService> discoveryReference;
    private ScheduledExecutorService pumpExecutor;
    private ExecutorService consumeExecutor;
    private volatile boolean stopped;

    /** A subscription as declared by the user, expanded per broker at runtime. */
    private static class Template {
        final String topic;
        final int queueId;
        final MessageListener listener;

        Template(String topic, int queueId, MessageListener listener) {
            this.topic = topic;
            this.queueId = queueId;
            this.listener = listener;
        }
    }

    private static class BrokerConnection {
        final String address;
        final ReferenceConfig<StoreService> referenceConfig;
        final StoreService storeService;
        final String brokerName;

        BrokerConnection(String address, ReferenceConfig<StoreService> referenceConfig,
                         StoreService storeService, String brokerName) {
            this.address = address;
            this.referenceConfig = referenceConfig;
            this.storeService = storeService;
            this.brokerName = brokerName;
        }
    }

    private static class Subscription {
        final BrokerConnection broker;
        final String topic;
        final int queueId;
        final MessageListener listener;
        volatile long pullOffset;
        volatile boolean active = true;
        final AtomicInteger cachedCount = new AtomicInteger(0);

        Subscription(BrokerConnection broker, String topic, int queueId, MessageListener listener) {
            this.broker = broker;
            this.topic = topic;
            this.queueId = queueId;
            this.listener = listener;
        }
    }

    public StoreConsumer(String directUrl, String consumerGroup) {
        this(directUrl, consumerGroup, "fastjson2");
    }

    /** Direct mode; directUrl may list several brokers comma-separated. */
    public StoreConsumer(String directUrl, String consumerGroup, String serialization) {
        this.directUrls = directUrl;
        this.registries = null;
        this.serialization = serialization;
        this.consumerGroup = consumerGroup;
    }

    public StoreConsumer(List<RegistryConfig> registries, String consumerGroup) {
        this(registries, consumerGroup, "fastjson2");
    }

    /** Registry mode: brokers are discovered and tracked dynamically. */
    public StoreConsumer(List<RegistryConfig> registries, String consumerGroup, String serialization) {
        this.directUrls = null;
        this.registries = registries;
        this.serialization = serialization;
        this.consumerGroup = consumerGroup;
    }

    /** Static subscription; call before {@link #start()}. Applies to every broker. */
    public void subscribe(String topic, int queueId, MessageListener listener) {
        templates.put(topic + "@" + queueId, new Template(topic, queueId, listener));
    }

    public void start() {
        ThreadFactory pumpFactory = r -> {
            Thread t = new Thread(r, "StoreConsumerPump");
            t.setDaemon(true);
            return t;
        };
        ThreadFactory consumeFactory = r -> {
            Thread t = new Thread(r, "StoreConsumerWorker");
            t.setDaemon(true);
            return t;
        };
        pumpExecutor = Executors.newScheduledThreadPool(1, pumpFactory);
        consumeExecutor = Executors.newFixedThreadPool(2, consumeFactory);
        if (registries == null) {
            for (String address : directUrls.split(",")) {
                addBroker(address.trim());
            }
        } else {
            discoveryReference = baseReference();
            discoveryReference.setRegistries(registries);
            discoveryReference.setCheck(false);
            // init the directory; the proxy itself is never called — pulls go
            // through the per-broker direct references
            discoveryReference.getRef();
            pumpExecutor.scheduleAtFixedRate(this::refreshBrokers, 0,
                    DISCOVERY_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        logger.info("StoreConsumer started, {} template(s), mode={}", templates.size(),
                registries == null ? "direct" : "registry");
    }

    private ReferenceConfig<StoreService> baseReference() {
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization(serialization);

        ReferenceConfig<StoreService> reference = new ReferenceConfig<>();
        reference.setInterface(StoreService.class);
        reference.setApplication("jaws-store-consumer");
        reference.setModule("store");
        reference.setGroup(StoreBroker.GROUP);
        reference.setVersion(StoreBroker.VERSION);
        // The RPC timeout must outlive the broker-side hold (plus its periodic
        // scan fallback), otherwise every idle long poll surfaces as a client
        // timeout. RocketMQ keeps these as a fixed 15s/30s pair; keep the same
        // 2x margin for a raised hold so the pair can never invert.
        reference.setRequestTimeout((int) Math.max(CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND,
                suspendTimeoutMillis * 2));
        reference.setProtocol(protocolConfig);
        return reference;
    }

    /** Connect one broker, learn its identity and expand every template onto it. */
    private void addBroker(String address) {
        if (stopped || brokers.containsKey(address)) {
            return;
        }
        ReferenceConfig<StoreService> reference = baseReference();
        reference.setDirectUrl(address);
        StoreService storeService = reference.getRef();
        String brokerName;
        try {
            BrokerInfo info = storeService.brokerInfoAsync().get(5, TimeUnit.SECONDS);
            brokerName = info.getBrokerName();
        } catch (Exception e) {
            reference.destroy();
            throw new IllegalStateException("Failed to fetch broker identity from " + address, e);
        }
        for (BrokerConnection existing : brokers.values()) {
            if (existing.brokerName.equals(brokerName)) {
                reference.destroy();
                throw new IllegalStateException("Duplicate brokerName '" + brokerName + "' at " + address
                        + " and " + existing.address + "; multi-master needs distinct names");
            }
        }
        BrokerConnection connection = new BrokerConnection(address, reference, storeService, brokerName);
        Map<String, Long> starts;
        try {
            starts = startOffsets(connection);
        } catch (RuntimeException e) {
            // Half attaching is worse than not attaching: a subscription without a
            // decided position would have to guess one.
            reference.destroy();
            throw e;
        }
        brokers.put(address, connection);
        for (Template template : templates.values()) {
            Subscription subscription = new Subscription(connection, template.topic, template.queueId, template.listener);
            subscription.pullOffset = starts.get(template.topic + "@" + template.queueId);
            subscriptions.put(brokerName + "@" + template.topic + "@" + template.queueId, subscription);
            pumpExecutor.execute(() -> pump(subscription));
        }
        logger.info("Broker attached: {} ({}), {} subscription(s)", brokerName, address, templates.size());
    }

    /**
     * Decide where every template starts on one broker, before any pump runs. Each
     * round goes out in full before any answer is awaited, so a broker costs a
     * round trip per round rather than per queue.
     *
     * <p>A stored offset wins outright — that is where the group stands, whoever
     * wrote it. A queue the group has never committed to is a new group on this
     * broker, and starts at the queue's tail: like RocketMQ's
     * CONSUME_FROM_LAST_OFFSET, it skips a backlog nobody asked it to process
     * rather than replaying history the first time it is seen.
     *
     * @throws IllegalStateException when an answer cannot be read, so the caller
     *         leaves the broker unattached and a later discovery round retries it
     */
    private Map<String, Long> startOffsets(BrokerConnection connection) {
        if (templates.isEmpty()) {
            return Map.of();
        }
        Map<String, CompletableFuture<OffsetResult>> queries = new HashMap<>();
        for (Template template : templates.values()) {
            queries.put(template.topic + "@" + template.queueId,
                    connection.storeService.queryOffsetAsync(consumerGroup, template.topic, template.queueId));
        }
        Map<String, Long> starts = new HashMap<>();
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<OffsetResult>> entry : queries.entrySet()) {
            OffsetResult result = await(entry.getValue(), "offset query", connection.brokerName, entry.getKey());
            if (result == null) {
                throw new IllegalStateException("Empty offset answer for " + entry.getKey()
                        + " on " + connection.brokerName);
            }
            if (result.isFound()) {
                starts.put(entry.getKey(), result.getOffset());
            } else {
                fresh.add(entry.getKey());
            }
        }
        if (fresh.isEmpty()) {
            return starts;
        }
        Map<String, CompletableFuture<Long>> tails = new HashMap<>();
        for (String key : fresh) {
            Template template = templates.get(key);
            tails.put(key, connection.storeService.maxOffsetAsync(template.topic, template.queueId));
        }
        Map<String, Long> bootstrapped = new HashMap<>();
        for (Map.Entry<String, CompletableFuture<Long>> entry : tails.entrySet()) {
            Long tail = await(entry.getValue(), "max offset query", connection.brokerName, entry.getKey());
            if (tail == null) {
                throw new IllegalStateException("Empty max offset answer for " + entry.getKey()
                        + " on " + connection.brokerName);
            }
            starts.put(entry.getKey(), tail);
            bootstrapped.put(entry.getKey(), tail);
        }
        logger.info("Group {} is new on {}, starting {} queue(s) at the tail: {}",
                consumerGroup, connection.brokerName, bootstrapped.size(), bootstrapped);
        return starts;
    }

    /** One bounded answer, or an exception naming the queue it belongs to. */
    private <T> T await(CompletableFuture<T> future, String what, String brokerName, String key) {
        try {
            return future.get(OFFSET_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted on " + what + " for " + key + " on " + brokerName, e);
        } catch (Exception e) {
            throw new IllegalStateException(what + " failed for " + key + " on " + brokerName, e);
        }
    }

    /**
     * Mirror progress onto the broker that owns the queue. Fire-and-forget: the
     * pump already moved ahead in memory, so a failed commit costs a server-side
     * gap that the next successful commit closes — and, if the process dies
     * inside that window, a replay of the difference.
     *
     * <p>Commits from concurrent batches may reach the broker out of order;
     * increaseOnly makes that harmless, since the highest offset always wins.
     */
    private void commitOffset(Subscription subscription, long offset, boolean increaseOnly) {
        if (!subscription.active) {
            return;
        }
        subscription.broker.storeService.commitOffsetAsync(consumerGroup, subscription.topic,
                        subscription.queueId, offset, increaseOnly)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.warn("Offset commit failed for {}@{}@{} = {}",
                                subscription.broker.brokerName, subscription.topic, subscription.queueId, offset, ex);
                    }
                });
    }

    /** Drop one broker's pumps and connection; its offsets stay for its return. */
    private void removeBroker(String address) {
        BrokerConnection connection = brokers.remove(address);
        if (connection == null) {
            return;
        }
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            if (entry.getValue().broker == connection) {
                entry.getValue().active = false;
                subscriptions.remove(entry.getKey());
            }
        }
        connection.referenceConfig.destroy();
        logger.info("Broker detached: {} ({})", connection.brokerName, address);
    }

    /**
     * Diff the registry's current provider list against the attached brokers.
     * Runs on the single-threaded pump executor, serialized with pump re-arms;
     * exceptions are caught so a transient failure never kills the schedule.
     */
    private void refreshBrokers() {
        if (stopped) {
            return;
        }
        try {
            Set<String> discovered = new HashSet<>();
            for (Reference<StoreService> reference : discoveryReference.getConsumerCoordinators()
                    .get(0).getCluster().getReferences()) {
                discovered.add(reference.getServiceUrl().getHostPort());
            }
            for (String address : discovered) {
                if (!brokers.containsKey(address)) {
                    try {
                        addBroker(address);
                    } catch (Exception e) {
                        logger.warn("Failed to attach broker {}, retry next refresh", address, e);
                    }
                }
            }
            for (String address : brokers.keySet()) {
                if (!discovered.contains(address)) {
                    removeBroker(address);
                }
            }
        } catch (Exception e) {
            logger.warn("Broker discovery refresh failed, retry next interval", e);
        }
    }

    private void pump(Subscription subscription) {
        if (stopped || !subscription.active) {
            return;
        }
        if (subscription.cachedCount.get() >= pullThresholdForQueue) {
            pumpExecutor.schedule(() -> pump(subscription), 50, TimeUnit.MILLISECONDS);
            return;
        }
        subscription.broker.storeService.pullAsync(subscription.topic, subscription.queueId, subscription.pullOffset,
                        pullBatchSize, suspendTimeoutMillis)
                .whenComplete((result, ex) -> onPullResult(subscription, result, ex));
    }

    private void onPullResult(Subscription subscription, PullResult result, Throwable ex) {
        if (stopped || !subscription.active) {
            return;
        }
        String brokerName = subscription.broker.brokerName;
        if (ex != null) {
            logger.warn("Pull failed for {}@{}@{}, retry in 1s", brokerName, subscription.topic, subscription.queueId, ex);
            pumpExecutor.schedule(() -> pump(subscription), 1000, TimeUnit.MILLISECONDS);
            return;
        }
        boolean emptyShortPoll = false;
        switch (result.getStatus()) {
            case FOUND -> {
                long nextOffset = result.getNextBeginOffset();
                subscription.pullOffset = nextOffset;
                List<MessageView> messages = result.getMessages();
                subscription.cachedCount.addAndGet(messages.size());
                consumeExecutor.execute(() -> {
                    try {
                        subscription.listener.onMessage(messages);
                    } catch (Throwable t) {
                        logger.error("Listener failed for {}@{}@{}, offset not advanced",
                                brokerName, subscription.topic, subscription.queueId, t);
                        return;
                    }
                    subscription.cachedCount.addAndGet(-messages.size());
                    commitOffset(subscription, nextOffset, true);
                });
            }
            case NO_NEW_MSG -> {
                subscription.pullOffset = result.getNextBeginOffset();
                // Short polling: an empty read returns instantly, so flag the tail to
                // back off. Long polling blocks the round-trip up to the hold window,
                // which is itself the throttle, so no backoff is needed there.
                emptyShortPoll = suspendTimeoutMillis <= 0;
            }
            case OFFSET_ILLEGAL -> {
                logger.warn("Offset reset for {}@{}@{}: {} -> {}", brokerName, subscription.topic, subscription.queueId,
                        subscription.pullOffset, result.getNextBeginOffset());
                subscription.pullOffset = result.getNextBeginOffset();
                // A reset may move backwards (the requested offset was already
                // cleaned), so this one must overwrite rather than merge upward.
                commitOffset(subscription, result.getNextBeginOffset(), false);
            }
        }
        // Re-arm the pump. In long-polling mode the round-trip already blocked on the
        // broker, so an immediate re-pump is correct. Only a short-polling empty read
        // backs off -- scheduled on the pump executor (like RocketMQ's
        // executePullRequestLater), never a blocking Thread.sleep.
        if (emptyShortPoll) {
            pumpExecutor.schedule(() -> pump(subscription), shortPollingIntervalMillis, TimeUnit.MILLISECONDS);
        } else {
            pumpExecutor.execute(() -> pump(subscription));
        }
    }

    public void shutdown() {
        stopped = true;
        if (pumpExecutor != null) {
            pumpExecutor.shutdownNow();
        }
        if (consumeExecutor != null) {
            consumeExecutor.shutdown();
            try {
                if (!consumeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    consumeExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                consumeExecutor.shutdownNow();
            }
        }
        for (BrokerConnection connection : brokers.values()) {
            connection.referenceConfig.destroy();
        }
        brokers.clear();
        if (discoveryReference != null) {
            discoveryReference.destroy();
        }
        logger.info("StoreConsumer stopped");
    }

    public void setPullBatchSize(int pullBatchSize) {
        this.pullBatchSize = pullBatchSize;
    }

    public void setPullThresholdForQueue(int pullThresholdForQueue) {
        this.pullThresholdForQueue = pullThresholdForQueue;
    }

    public void setSuspendTimeoutMillis(long suspendTimeoutMillis) {
        this.suspendTimeoutMillis = suspendTimeoutMillis;
    }

    /** Round gap applied only in short-polling mode; ignored while a hold is requested. */
    public void setShortPollingIntervalMillis(long shortPollingIntervalMillis) {
        this.shortPollingIntervalMillis = shortPollingIntervalMillis;
    }
}