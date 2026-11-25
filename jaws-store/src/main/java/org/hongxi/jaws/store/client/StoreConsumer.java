package org.hongxi.jaws.store.client;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.store.MessageView;
import org.hongxi.jaws.store.PullResult;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
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
 * <p>Flow control: when dispatched-but-unconsumed messages reach
 * pullThresholdForQueue the pump pauses briefly — the consumer's own
 * backpressure gate, so broker speed never floods the listener.
 *
 * <p>Deliberately absent vs RocketMQ consumer: rebalance (queues are
 * subscribed statically), ProcessQueue sliding-window reorder, retry/DLQ.
 */
public class StoreConsumer {

    private static final Logger logger = LoggerFactory.getLogger(StoreConsumer.class);

    private final ReferenceConfig<StoreService> referenceConfig;
    private StoreService storeService;
    private final LocalOffsetStore offsetStore;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    private int pullBatchSize = 32;
    private int pullThresholdForQueue = 1000;
    private long suspendTimeoutMillis = 5000;

    private ScheduledExecutorService pumpExecutor;
    private ExecutorService consumeExecutor;
    private volatile boolean stopped;

    private static class Subscription {
        final String topic;
        final int queueId;
        final MessageListener listener;
        volatile long pullOffset;
        final AtomicInteger cachedCount = new AtomicInteger(0);

        Subscription(String topic, int queueId, MessageListener listener) {
            this.topic = topic;
            this.queueId = queueId;
            this.listener = listener;
        }
    }

    public StoreConsumer(String directUrl, String consumerGroup) {
        this(directUrl, consumerGroup, "fastjson2");
    }

    public StoreConsumer(String directUrl, String consumerGroup, String serialization) {
        this.offsetStore = new LocalOffsetStore(consumerGroup);
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization(serialization);

        referenceConfig = new ReferenceConfig<>();
        referenceConfig.setInterface(StoreService.class);
        referenceConfig.setApplication("jaws-store-consumer");
        referenceConfig.setModule("store");
        referenceConfig.setGroup(StoreBroker.GROUP);
        referenceConfig.setVersion(StoreBroker.VERSION);
        referenceConfig.setProtocol(protocolConfig);
        referenceConfig.setDirectUrl(directUrl);
    }

    /** Static subscription; call before {@link #start()}. */
    public void subscribe(String topic, int queueId, MessageListener listener) {
        subscriptions.put(topic + "@" + queueId, new Subscription(topic, queueId, listener));
    }

    public void start() {
        // the RPC timeout must outlive the broker-side hold, otherwise every
        // idle long poll would surface as a client timeout
        referenceConfig.setRequestTimeout((int) suspendTimeoutMillis + 5000);
        storeService = referenceConfig.getRef();
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
        for (Subscription subscription : subscriptions.values()) {
            subscription.pullOffset = offsetStore.get(subscription.topic, subscription.queueId);
            pumpExecutor.execute(() -> pump(subscription));
        }
        logger.info("StoreConsumer started, {} subscription(s)", subscriptions.size());
    }

    private void pump(Subscription subscription) {
        if (stopped) {
            return;
        }
        if (subscription.cachedCount.get() >= pullThresholdForQueue) {
            pumpExecutor.schedule(() -> pump(subscription), 50, TimeUnit.MILLISECONDS);
            return;
        }
        storeService.pullAsync(subscription.topic, subscription.queueId, subscription.pullOffset,
                        pullBatchSize, suspendTimeoutMillis)
                .whenComplete((result, ex) -> onPullResult(subscription, result, ex));
    }

    private void onPullResult(Subscription subscription, PullResult result, Throwable ex) {
        if (stopped) {
            return;
        }
        if (ex != null) {
            logger.warn("Pull failed for {}@{}, retry in 1s", subscription.topic, subscription.queueId, ex);
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
                        logger.error("Listener failed for {}@{}, offset not advanced",
                                subscription.topic, subscription.queueId, t);
                        return;
                    }
                    subscription.cachedCount.addAndGet(-messages.size());
                    offsetStore.update(subscription.topic, subscription.queueId, nextOffset);
                });
            }
            case NO_NEW_MSG -> subscription.pullOffset = result.getNextBeginOffset();
            case OFFSET_ILLEGAL -> {
                logger.warn("Offset reset for {}@{}: {} -> {}", subscription.topic, subscription.queueId,
                        subscription.pullOffset, result.getNextBeginOffset());
                subscription.pullOffset = result.getNextBeginOffset();
                offsetStore.update(subscription.topic, subscription.queueId, result.getNextBeginOffset());
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
        referenceConfig.destroy();
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