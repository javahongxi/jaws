package org.hongxi.jaws.store.client;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.exception.JawsAbstractException;
import org.hongxi.jaws.store.BrokerInfo;
import org.hongxi.jaws.store.LeaseResult;
import org.hongxi.jaws.store.MessageView;
import org.hongxi.jaws.store.OffsetResult;
import org.hongxi.jaws.store.PullResult;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>Subscription type: Exclusive, in Pulsar's vocabulary — one live consumer per
 * group, and parallelism comes from the queue count (the master shards), never from
 * adding consumers to one queue. The cumulative position is what pins this shape:
 * the group's progress is a single offset meaning "done up to here", which is only
 * meaningful for a single reader — Pulsar itself forbids cumulative acknowledgment
 * for its Shared and Key_Shared types.
 *
 * <p>Exclusivity is enforced on the broker, not left to convention. Each instance
 * carries a consumerId and, before a broker's pumps start, claims that broker's
 * lease for every subscribed queue; a queue already held by another live consumer
 * is refused, so a second concurrent instance of the group fails to attach rather
 * than silently receiving a duplicate stream. The claim is kept alive by a renew
 * ticker (every third of the lease) and handed back on shutdown; a crashed or
 * partitioned instance simply stops renewing, and once its lease lapses another
 * can claim it — its in-flight pulls and commits are then fenced off with
 * {@code NOT_LEASED}, so a zombie can never advance the group after handover.
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

    private static final Logger log = LoggerFactory.getLogger(StoreConsumer.class);

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

    /** This instance's identity in the group; the lease is granted to it. Distinct
     * per process by default, overridable so an operator can name a consumer. */
    private volatile String consumerId = defaultConsumerId();

    /** How long a claimed lease lasts before it must be renewed; the renew ticker
     * runs at a third of this so three ticks can be missed before the queue frees. */
    private long consumerLeaseMillis = 30_000;

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
        // Keep every claimed lease alive. Dispatched on the pump executor like the
        // pumps themselves, and non-blocking (fire-and-forget), so it never stalls a
        // re-arm; a renewal that lands after the lease lapsed simply reports denial
        // and the next pull drives the real re-claim-or-stand-down decision.
        long renewInterval = Math.max(1000, consumerLeaseMillis / 3);
        pumpExecutor.scheduleAtFixedRate(this::renewLeases, renewInterval, renewInterval, TimeUnit.MILLISECONDS);
        log.info("StoreConsumer started (consumerId={}), {} template(s), mode={}", consumerId,
                templates.size(), registries == null ? "direct" : "registry");
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
            // Claim before any pump runs: a queue another live consumer holds must
            // leave this broker unattached rather than half-started, the same rule that
            // keeps a failed offset read from guessing a position.
            claimLeases(connection);
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
        log.info("Broker attached: {} ({}), {} subscription(s)", brokerName, address, templates.size());
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
        log.info("Group {} is new on {}, starting {} queue(s) at the tail: {}",
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
     * Take this broker's lease for every subscribed queue, before any pump is armed.
     * A denial means another live consumer of the same group already holds that queue:
     * the queues claimed so far on this broker are handed back and the caller leaves
     * the broker unattached — so a second instance never consumes alongside the first.
     * Re-claiming a queue this same consumerId already holds is granted (idempotent),
     * so a registry re-attach of a surviving instance does not fight itself.
     */
    private void claimLeases(BrokerConnection connection) {
        List<Template> claimed = new ArrayList<>();
        for (Template template : templates.values()) {
            LeaseResult result;
            try {
                result = connection.storeService.claimLeaseAsync(consumerGroup, template.topic, template.queueId,
                        consumerId, consumerLeaseMillis).get(OFFSET_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseLeases(connection, claimed);
                throw new IllegalStateException("Interrupted claiming lease for " + template.topic + "@"
                        + template.queueId + " on " + connection.brokerName, e);
            } catch (Exception e) {
                releaseLeases(connection, claimed);
                throw new IllegalStateException("Lease claim failed for " + template.topic + "@"
                        + template.queueId + " on " + connection.brokerName, e);
            }
            if (!result.isGranted()) {
                releaseLeases(connection, claimed);
                throw new IllegalStateException("Queue " + template.topic + "@" + template.queueId + " on "
                        + connection.brokerName + " is held by another consumer of group " + consumerGroup
                        + (result.getHolder() == null ? "" : " (" + result.getHolder() + ")"));
            }
            claimed.add(template);
        }
    }

    /**
     * Renew every live lease. Fire-and-forget on the pump executor: a renewal failure
     * is not acted on here — the next pull either re-claims (if the lapse was a stall
     * and nobody took the queue) or stands the pump down (if another consumer won it).
     */
    private void renewLeases() {
        if (stopped) {
            return;
        }
        for (Subscription subscription : subscriptions.values()) {
            if (!subscription.active) {
                continue;
            }
            subscription.broker.storeService.renewLeaseAsync(consumerGroup, subscription.topic,
                            subscription.queueId, consumerId)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Lease renew failed for {}@{}@{}",
                                    subscription.broker.brokerName, subscription.topic, subscription.queueId, ex);
                        } else if (!result.isGranted()) {
                            log.warn("Lease renew rejected for {}@{}@{}, a pull will re-claim or stand down",
                                    subscription.broker.brokerName, subscription.topic, subscription.queueId);
                        }
                    });
        }
    }

    /** Give back the queues this instance claimed on one broker (attach rollback, shutdown). */
    private void releaseLeases(BrokerConnection connection, List<Template> templatesToRelease) {
        for (Template template : templatesToRelease) {
            try {
                connection.storeService.releaseLeaseAsync(consumerGroup, template.topic, template.queueId, consumerId)
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Lease release failed for {}@{}@{} on {}",
                        connection.brokerName, template.topic, template.queueId, consumerId, e);
            }
        }
    }

    /** Distinct-per-process default identity: hostname plus a random suffix. */
    private static String defaultConsumerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID();
    }

    /**
     * Whether a failed lease hand-back on shutdown means the broker simply could not
     * be reached, rather than rejecting the call. A live broker answers this
     * value-returning RPC without throwing, so any throwable here is a delivery
     * failure: no live reference to route to, a broken/unconnectable socket, or a
     * reply that never arrived. Those all leave the broker's in-memory lease to lapse
     * on its own, so the hand-back is harmless and should not warn.
     */
    private static boolean isBrokerUnreachable(Throwable e) {
        Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
        return cause instanceof TimeoutException
                || cause instanceof java.io.IOException
                || cause instanceof JawsAbstractException;
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
        subscription.broker.storeService.commitOffsetAsync(consumerGroup, consumerId, subscription.topic,
                        subscription.queueId, offset, increaseOnly)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Offset commit failed for {}@{}@{} = {}",
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
        log.info("Broker detached: {} ({})", connection.brokerName, address);
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
                        log.warn("Failed to attach broker {}, retry next refresh", address, e);
                    }
                }
            }
            for (String address : brokers.keySet()) {
                if (!discovered.contains(address)) {
                    removeBroker(address);
                }
            }
        } catch (Exception e) {
            log.warn("Broker discovery refresh failed, retry next interval", e);
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
        subscription.broker.storeService.pullAsync(consumerGroup, consumerId, subscription.topic,
                        subscription.queueId, subscription.pullOffset,
                        pullBatchSize, suspendTimeoutMillis)
                .whenComplete((result, ex) -> onPullResult(subscription, result, ex));
    }

    private void onPullResult(Subscription subscription, PullResult result, Throwable ex) {
        if (stopped || !subscription.active) {
            return;
        }
        String brokerName = subscription.broker.brokerName;
        if (ex != null) {
            log.warn("Pull failed for {}@{}@{}, retry in 1s", brokerName, subscription.topic, subscription.queueId, ex);
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
                        log.error("Listener failed for {}@{}@{}, offset not advanced",
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
                log.warn("Offset reset for {}@{}@{}: {} -> {}", brokerName, subscription.topic, subscription.queueId,
                        subscription.pullOffset, result.getNextBeginOffset());
                subscription.pullOffset = result.getNextBeginOffset();
                // A reset may move backwards (the requested offset was already
                // cleaned), so this one must overwrite rather than merge upward.
                commitOffset(subscription, result.getNextBeginOffset(), false);
            }
            case NOT_LEASED -> {
                // This instance no longer holds the queue. Try to reclaim: if the lapse
                // was a local stall and nobody took the queue, the claim is granted and
                // the pump resumes; if another consumer holds it, the claim is denied and
                // this pump stands down for good — exclusivity means one reader per queue.
                LeaseResult reclaim;
                try {
                    reclaim = subscription.broker.storeService.claimLeaseAsync(consumerGroup, subscription.topic,
                            subscription.queueId, consumerId, consumerLeaseMillis)
                            .get(OFFSET_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    reclaim = null;
                }
                if (reclaim != null && reclaim.isGranted()) {
                    log.info("Reclaimed lease for {}@{}@{}, resuming pump", brokerName,
                            subscription.topic, subscription.queueId);
                    pumpExecutor.execute(() -> pump(subscription));
                } else {
                    subscription.active = false;
                    log.error("Lost lease for {}@{}@{}: consumer {} is not the holder{}, pump stopped",
                            brokerName, subscription.topic, subscription.queueId, consumerId,
                            reclaim == null ? " (reclaim unreachable)" : " (held by " + reclaim.getHolder() + ")");
                }
                return;
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
        // Hand the queues back before the connections go, so a clean stop does not
        // make the next instance of the group wait out the lease. Best-effort: when the
        // broker is already gone the hand-back cannot land, and its in-memory lease dies
        // with it — that is the expected path, not a fault, so it is skipped quietly
        // (debug, no stack) rather than alarming on every shutdown. A reachable broker
        // answers this value-returning call without throwing, so only an undeliverable
        // one (no live reference, connect failure, or reply timeout) counts as gone.
        for (Subscription subscription : subscriptions.values()) {
            if (!subscription.active) {
                continue;
            }
            try {
                subscription.broker.storeService.releaseLeaseAsync(consumerGroup, subscription.topic,
                        subscription.queueId, consumerId).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (isBrokerUnreachable(e)) {
                    log.debug("Skipping lease hand-back for {}@{}@{}: broker unreachable",
                            subscription.broker.brokerName, subscription.topic, subscription.queueId);
                } else {
                    log.warn("Lease release on shutdown failed for {}@{}@{}",
                            subscription.broker.brokerName, subscription.topic, subscription.queueId, e);
                }
            }
        }
        for (BrokerConnection connection : brokers.values()) {
            connection.referenceConfig.destroy();
        }
        brokers.clear();
        subscriptions.clear();
        if (discoveryReference != null) {
            discoveryReference.destroy();
        }
        log.info("StoreConsumer stopped");
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

    /** This instance's identity in the group; set before start to name a consumer. */
    public void setConsumerId(String consumerId) {
        this.consumerId = consumerId;
    }

    public String getConsumerId() {
        return consumerId;
    }

    /**
     * Lease duration claimed per queue, applied at start. Must exceed the round trip
     * it must survive; the renew ticker runs at a third of it. Set short in tests to
     * watch a lapse hand the queue over without waiting on a real crash.
     */
    public void setConsumerLeaseMillis(long consumerLeaseMillis) {
        this.consumerLeaseMillis = Math.max(1000, consumerLeaseMillis);
    }

    public long getConsumerLeaseMillis() {
        return consumerLeaseMillis;
    }
}