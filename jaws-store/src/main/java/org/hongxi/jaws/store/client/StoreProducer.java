package org.hongxi.jaws.store.client;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.store.PutResult;
import org.hongxi.jaws.store.StoreBroker;
import org.hongxi.jaws.store.StoreMessage;
import org.hongxi.jaws.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin producer over the jaws reference proxy. The wire call is always
 * async; the three send semantics are client-side sugar:
 * sync = future.get with timeout, async = the future itself, oneway = fire
 * and forget with a failure logger.
 *
 * <p>An inflight semaphore caps concurrent wire calls: without it a tight
 * async loop outruns the broker's bounded business pool, whose fail-fast
 * rejections then trip the client-side error fusing. Blocking on a full
 * window is the backpressure RocketMQ's async path gets from its own
 * semaphore-plus-retry machinery, reduced to its simplest form here.
 *
 * <p>Deliberately absent vs RocketMQ producer: autoBatch, compression and
 * MQFaultStrategy — cost-amortization knobs discussed in doc/rocketmq stay
 * article-level knowledge, not code here.
 */
public class StoreProducer {

    private static final Logger logger = LoggerFactory.getLogger(StoreProducer.class);

    /** Default cap on concurrent wire calls (inflight window). */
    public static final int DEFAULT_MAX_INFLIGHT = 512;

    private final ReferenceConfig<StoreService> referenceConfig;
    private final StoreService storeService;
    private final int requestTimeout;
    private final Semaphore inflight;

    public StoreProducer(String directUrl) {
        this(directUrl, "fastjson2", 3000);
    }

    public StoreProducer(String directUrl, String serialization, int requestTimeout) {
        this(directUrl, serialization, requestTimeout, DEFAULT_MAX_INFLIGHT);
    }

    public StoreProducer(String directUrl, String serialization, int requestTimeout, int maxInflight) {
        this.requestTimeout = requestTimeout;
        this.inflight = new Semaphore(maxInflight);
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(JawsConstants.PROTOCOL_JAWS);
        protocolConfig.setId(protocolConfig.getName());
        protocolConfig.setTransportFactory("netty");
        protocolConfig.setSerialization(serialization);

        referenceConfig = new ReferenceConfig<>();
        referenceConfig.setInterface(StoreService.class);
        referenceConfig.setApplication("jaws-store-producer");
        referenceConfig.setModule("store");
        referenceConfig.setGroup(StoreBroker.GROUP);
        referenceConfig.setVersion(StoreBroker.VERSION);
        referenceConfig.setRequestTimeout(requestTimeout);
        referenceConfig.setProtocol(protocolConfig);
        referenceConfig.setDirectUrl(directUrl);
        storeService = referenceConfig.getRef();
    }

    /** Block until the durability barrier completes or the timeout fires. */
    public PutResult sendSync(StoreMessage message) {
        acquireInflight();
        try {
            return storeService.putAsync(message).get(requestTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Send interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Send failed: " + e.getMessage(), e);
        } finally {
            inflight.release();
        }
    }

    public CompletableFuture<PutResult> sendAsync(StoreMessage message) {
        acquireInflight();
        try {
            return storeService.putAsync(message).whenComplete((r, t) -> inflight.release());
        } catch (RuntimeException e) {
            inflight.release();
            throw e;
        }
    }

    /** Fire and forget; failures are logged, never propagated. */
    public void sendOneway(StoreMessage message) {
        acquireInflight();
        try {
            storeService.putAsync(message).whenComplete((result, ex) -> {
                inflight.release();
                if (ex != null) {
                    logger.warn("Oneway send failed", ex);
                } else if (!result.isOk()) {
                    logger.warn("Oneway send rejected: {}", result.getStatus());
                }
            });
        } catch (RuntimeException e) {
            inflight.release();
            throw e;
        }
    }

    private void acquireInflight() {
        try {
            inflight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Send interrupted while waiting for inflight window", e);
        }
    }

    public void shutdown() {
        referenceConfig.destroy();
    }
}