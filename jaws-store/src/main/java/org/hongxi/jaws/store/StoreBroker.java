package org.hongxi.jaws.store;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.config.ServiceConfig;
import org.hongxi.jaws.serialization.FastJson2Serialization;
import org.hongxi.jaws.serialization.ObjectInput;
import org.hongxi.jaws.serialization.ObjectOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Assembles the storage engine and exposes it over the jaws protocol —
 * the store's broker side. Two deployment shapes:
 * direct-connect (no registry, single instance) or registry-registered
 * multi-master sharding — each instance owns an independent CommitLog and
 * a stable brokerName; there is no replication between instances.
 *
 * <p>Alongside the log it hosts {@link ConsumerOffsetStore} and
 * {@link ConsumerLeaseStore}: a group's consumption progress and its exclusive
 * per-queue consumer lease both live on the broker that owns the queue rather
 * than inside one client instance. The lease is what enforces Exclusive — a pull
 * or commit from a consumer that does not hold it is refused (see
 * {@link StoreService#pullAsync}).
 *
 * <p>Typical scenario: embedded in a demo main or a test to run a real
 * broker on a local port, with StoreProducer/StoreConsumer on the other end.
 */
public class StoreBroker {

    private static final Logger logger = LoggerFactory.getLogger(StoreBroker.class);

    public static final String GROUP = "store";
    public static final String VERSION = "1.0";

    private final MessageStore messageStore;
    private final ConsumerOffsetStore offsetStore;
    private final ConsumerLeaseStore leaseStore;
    private final ServiceConfig<StoreService> serviceConfig;
    private final String serialization;
    private final String brokerName;
    private final int port;
    /**
     * Dedicated pool that runs the append path (serialize-free but {@code putMessageLock}
     * bound). Offloading it here — rather than letting it run inline on the shared RPC
     * dispatch pool — is what keeps a write burst from parking every dispatch worker on the
     * global lock and starving reads with a 40002. Throughput stays lock-bound (this adds no
     * speed); the pool only isolates, mirroring RocketMQ's separate sendMessageThreadPool.
     * Overflow rejects with a clean broker-busy code instead of an RPC pool exhaustion.
     */
    private final ThreadPoolExecutor writeExecutor;

    public StoreBroker(StoreConfig storeConfig, int port) {
        this(storeConfig, port, "fastjson2");
    }

    public StoreBroker(StoreConfig storeConfig, int port, String serialization) {
        this(storeConfig, port, serialization, null);
    }

    /** Registry mode: export and register to every given registry. */
    public StoreBroker(StoreConfig storeConfig, int port, String serialization, List<RegistryConfig> registries) {
        this.messageStore = new MessageStore(storeConfig);
        this.offsetStore = new ConsumerOffsetStore(storeConfig);
        this.leaseStore = new ConsumerLeaseStore(storeConfig);
        this.serialization = serialization;
        this.brokerName = storeConfig.getBrokerName();
        this.port = port;

        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization(serialization);
        protocolConfig.setPort(port);
        // A storage broker lives on bursts. Three buffers, mirroring RocketMQ: (1) the RPC
        // dispatch queue is deep (RocketMQ's sendThreadPoolQueueCapacity=10000) so a burst
        // is absorbed rather than fail-fasted; (2) the dispatch pool is elastic — a modest
        // core (min) that grows to a full ceiling (max) on a wave, then decays back, since
        // with writes offloaded (below) its steady job is only decode + short/ suspended
        // reads, far under the old flat 200; (3) the append path runs on a bounded
        // writeExecutor so writes cannot starve the dispatch workers serving pulls/queries.
        protocolConfig.setWorkerQueueSize(10_000);
        protocolConfig.setMinWorkerThreads(32);
        protocolConfig.setMaxWorkerThreads(200);
        // Append is lock-serialized, so a handful of feeding threads already saturate the
        // global lock; more would only queue on it. Size to CPU, cap the in-flight depth so
        // sustained overload sheds with SERVICE_NOT_AVAILABLE rather than an unbounded queue.
        int writeThreads = Math.max(4, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        this.writeExecutor = new ThreadPoolExecutor(writeThreads, writeThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10_000),
                r -> {
                    Thread t = new Thread(r, "StorePutWorker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        // The provider-side async guard (DefaultProvider's orTimeout) defaults
        // to 1000ms, far below the long-poll hold (15s) and its 5s scan
        // fallback, so every suspended pull would surface as a 40003 provider
        // timeout. Bound it above the worst-case hold yet below the consumer's
        // 30s RPC budget: a genuinely stuck pull then reports a clean provider
        // timeout instead of racing the client's own deadline.
        protocolConfig.setParameter(UrlParam.Transport.REQUEST_TIMEOUT.getName(), "25000");

        serviceConfig = new ServiceConfig<>();
        serviceConfig.setRef(new StoreServiceImpl());
        serviceConfig.setApplication("jaws-store");
        serviceConfig.setModule("store");
        serviceConfig.setInterface(StoreService.class);
        serviceConfig.setGroup(GROUP);
        serviceConfig.setVersion(VERSION);
        serviceConfig.setProtocol(protocolConfig);
        if (registries != null && !registries.isEmpty()) {
            serviceConfig.setRegistries(registries);
        }
    }

    public void start() throws IOException {
        messageStore.start();
        offsetStore.start();
        leaseStore.start();
        warmUpSerialization();
        serviceConfig.export();
        logger.info("StoreBroker listening on port {}", port);
    }

    /**
     * Round-trip every wire POJO once before accepting traffic. The first
     * JSONB call triggers fastjson2's static initialization plus reader and
     * writer construction; under a cold burst all business threads pile up
     * on that class-init monitor at once and the pool exhausts before a
     * single request completes. Paying that cost here keeps it single-threaded.
     */
    private void warmUpSerialization() throws IOException {
        if (!"fastjson2".equals(serialization)) {
            return;
        }
        FastJson2Serialization codec = new FastJson2Serialization();
        Object[] samples = {
                StoreMessage.of("warmup", 0, "warmup"),
                new PutResult(PutStatus.PUT_OK, 0, 0),
                new PullResult(PullStatus.FOUND, 0, 0, 0),
                new MessageView(),
                new BrokerInfo("warmup"),
                OffsetResult.found(0),
                LeaseResult.granted()
        };
        for (Object sample : samples) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutput output = codec.serialize(bytes)) {
                output.writeObject(sample);
            }
            try (ObjectInput input = codec.deserialize(new ByteArrayInputStream(bytes.toByteArray()))) {
                input.readObject(sample.getClass());
            } catch (ClassNotFoundException e) {
                throw new IOException("Serialization warm-up failed for " + sample.getClass(), e);
            }
        }
        logger.info("Serialization warmed up for {} wire types", samples.length);
    }

    public void shutdown() {
        serviceConfig.unexport();
        writeExecutor.shutdown();
        leaseStore.shutdown();
        offsetStore.shutdown();
        messageStore.shutdown();
    }

    public MessageStore getMessageStore() {
        return messageStore;
    }

    public ConsumerOffsetStore getOffsetStore() {
        return offsetStore;
    }

    public ConsumerLeaseStore getLeaseStore() {
        return leaseStore;
    }

    public int getPort() {
        return port;
    }

    /** Relay an engine future's outcome onto the RPC-facing future (write-pool hop). */
    private static <T> void complete(CompletableFuture<T> future, T value, Throwable ex) {
        if (ex != null) {
            future.completeExceptionally(ex);
        } else {
            future.complete(value);
        }
    }

    private class StoreServiceImpl implements StoreService {

        @Override
        public CompletableFuture<PutResult> putAsync(StoreMessage message) {
            // Park the append on the write pool so the dispatch worker returns immediately
            // and a write burst cannot starve pulls/queries. A full write queue sheds with a
            // clean broker-busy code, matching the engine's own fuse responses.
            CompletableFuture<PutResult> future = new CompletableFuture<>();
            try {
                writeExecutor.execute(() -> messageStore.putMessageAsync(message)
                        .whenComplete((result, ex) -> complete(future, result, ex)));
            } catch (RejectedExecutionException busy) {
                future.complete(new PutResult(PutStatus.SERVICE_NOT_AVAILABLE));
            }
            return future;
        }

        @Override
        public CompletableFuture<List<PutResult>> putBatchAsync(List<StoreMessage> messages) {
            CompletableFuture<List<PutResult>> future = new CompletableFuture<>();
            try {
                writeExecutor.execute(() -> messageStore.putMessagesAsync(messages)
                        .whenComplete((results, ex) -> complete(future, results, ex)));
            } catch (RejectedExecutionException busy) {
                List<PutResult> refused = new ArrayList<>(messages.size());
                for (int i = 0; i < messages.size(); i++) {
                    refused.add(new PutResult(PutStatus.SERVICE_NOT_AVAILABLE));
                }
                future.complete(refused);
            }
            return future;
        }

        @Override
        public CompletableFuture<PullResult> pullAsync(String group, String consumerId, String topic, int queueId,
                                                       long offset, int maxCount, long suspendTimeoutMillis) {
            // Lease fence ahead of the log walk: a consumer that does not hold this
            // queue (never attached, or superseded) is turned away with NOT_LEASED, and
            // the request is never suspended, so a rejected pump costs nothing.
            if (!leaseStore.isHeld(group, topic, queueId, consumerId)) {
                return CompletableFuture.completedFuture(new PullResult(PullStatus.NOT_LEASED, offset, 0, 0));
            }
            return messageStore.getMessageAsync(topic, queueId, offset, maxCount, suspendTimeoutMillis);
        }

        @Override
        public CompletableFuture<LeaseResult> claimLeaseAsync(String group, String topic, int queueId,
                                                             String consumerId, long leaseMillis) {
            return CompletableFuture.completedFuture(leaseStore.claim(group, topic, queueId, consumerId, leaseMillis));
        }

        @Override
        public CompletableFuture<LeaseResult> renewLeaseAsync(String group, String topic, int queueId, String consumerId) {
            return CompletableFuture.completedFuture(leaseStore.renew(group, topic, queueId, consumerId));
        }

        @Override
        public CompletableFuture<Boolean> releaseLeaseAsync(String group, String topic, int queueId, String consumerId) {
            return CompletableFuture.completedFuture(leaseStore.release(group, topic, queueId, consumerId));
        }

        @Override
        public CompletableFuture<BrokerInfo> brokerInfoAsync() {
            return CompletableFuture.completedFuture(new BrokerInfo(brokerName));
        }

        @Override
        public CompletableFuture<OffsetResult> queryOffsetAsync(String group, String topic, int queueId) {
            // One map read, no log walk: answer inline rather than park a worker.
            Long offset = offsetStore.queryOffset(group, topic, queueId);
            return CompletableFuture.completedFuture(offset == null
                    ? OffsetResult.notFound() : OffsetResult.found(offset));
        }

        @Override
        public CompletableFuture<OffsetResult> commitOffsetAsync(String group, String consumerId, String topic,
                                                                int queueId, long offset, boolean increaseOnly) {
            // Lease fence: a caller outside the lease cannot move the group. Report the
            // authoritative offset unchanged so a superseded holder never advances past
            // where the current one stands; absence stays absence.
            if (!leaseStore.isHeld(group, topic, queueId, consumerId)) {
                Long current = offsetStore.queryOffset(group, topic, queueId);
                return CompletableFuture.completedFuture(
                        current == null ? OffsetResult.notFound() : OffsetResult.found(current));
            }
            long authoritative = offsetStore.updateOffset(group, topic, queueId, offset, increaseOnly);
            return CompletableFuture.completedFuture(OffsetResult.found(authoritative));
        }

        @Override
        public CompletableFuture<Long> maxOffsetAsync(String topic, int queueId) {
            // One concurrent-map read plus a volatile position; like the offset
            // queries it never touches the commit log, so it answers inline.
            return CompletableFuture.completedFuture(messageStore.getMaxOffsetInQueue(topic, queueId));
        }
    }
}