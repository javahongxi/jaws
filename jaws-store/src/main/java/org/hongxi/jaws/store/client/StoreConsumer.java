package org.hongxi.jaws.store.client;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.store.BrokerInfo;
import org.hongxi.jaws.store.MessageView;
import org.hongxi.jaws.store.PullResult;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * via brokerInfoAsync, and offsets are keyed brokerName@topic@queueId so the
 * same queue on different brokers never shares an offset. A broker going
 * away stops its pumps; its offsets survive for when it returns.
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

    private final String directUrls;
    private final List<RegistryConfig> registries;
    private final String serialization;
    private final LocalOffsetStore offsetStore;
    private final Map<String, Template> templates = new ConcurrentHashMap<>();
    private final Map<String, BrokerConnection> brokers = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    private int pullBatchSize = 32;
    private int pullThresholdForQueue = 1000;
    private long suspendTimeoutMillis = 5000;

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
        this.offsetStore = new LocalOffsetStore(consumerGroup);
    }

    public StoreConsumer(List<RegistryConfig> registries, String consumerGroup) {
        this(registries, consumerGroup, "fastjson2");
    }

    /** Registry mode: brokers are discovered and tracked dynamically. */
    public StoreConsumer(List<RegistryConfig> registries, String consumerGroup, String serialization) {
        this.directUrls = null;
        this.registries = registries;
        this.serialization = serialization;
        this.offsetStore = new LocalOffsetStore(consumerGroup);
    }

    /** Static subscription; call before {@link #start()}. Applies to every broker. */
    public void subscribe(String topic, int queueId, MessageListener listener) {
        templates.put(topic + "@" + queueId, new Template(topic, queueId, listener));
    }

    public void start() {
        offsetStore.load();
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
        pumpExecutor.scheduleAtFixedRate(offsetStore::persist, 1, 1, TimeUnit.SECONDS);
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
        // the RPC timeout must outlive the broker-side hold, otherwise every
        // idle long poll would surface as a client timeout
        reference.setRequestTimeout((int) suspendTimeoutMillis + 5000);
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
        brokers.put(address, connection);
        for (Template template : templates.values()) {
            Subscription subscription = new Subscription(connection, template.topic, template.queueId, template.listener);
            subscription.pullOffset = offsetStore.get(brokerName, template.topic, template.queueId);
            subscriptions.put(brokerName + "@" + template.topic + "@" + template.queueId, subscription);
            pumpExecutor.execute(() -> pump(subscription));
        }
        logger.info("Broker attached: {} ({}), {} subscription(s)", brokerName, address, templates.size());
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
                    offsetStore.update(brokerName, subscription.topic, subscription.queueId, nextOffset);
                });
            }
            case NO_NEW_MSG -> subscription.pullOffset = result.getNextBeginOffset();
            case OFFSET_ILLEGAL -> {
                logger.warn("Offset reset for {}@{}@{}: {} -> {}", brokerName, subscription.topic, subscription.queueId,
                        subscription.pullOffset, result.getNextBeginOffset());
                subscription.pullOffset = result.getNextBeginOffset();
                offsetStore.update(brokerName, subscription.topic, subscription.queueId, result.getNextBeginOffset());
            }
        }
        pumpExecutor.execute(() -> pump(subscription));
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
        offsetStore.persist();
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
}