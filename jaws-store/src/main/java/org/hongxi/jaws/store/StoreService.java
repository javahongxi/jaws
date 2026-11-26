package org.hongxi.jaws.store;

import java.util.concurrent.CompletableFuture;

/**
 * The store's RPC contract, carried by the jaws protocol. Both methods are
 * future-based end to end: the provider never blocks a business thread, and
 * the long-polling pull may stay pending for seconds — a load shape ordinary
 * RPC benchmarks never exercise.
 *
 * <p>Client-side send semantics (sync/async/oneway) are layered on top by
 * StoreProducer; the wire contract stays uniformly async.
 */
public interface StoreService {

    /** Append one message; completes after the configured durability barrier. */
    CompletableFuture<PutResult> putAsync(StoreMessage message);

    /**
     * Pull up to maxCount messages from offset. When nothing is new the
     * request is held for up to suspendTimeoutMillis (long polling);
     * 0 disables suspension and returns immediately.
     */
    CompletableFuture<PullResult> pullAsync(String topic, int queueId, long offset,
                                            int maxCount, long suspendTimeoutMillis);

    /** Report the stable broker identity; consumers key offsets by it. */
    CompletableFuture<BrokerInfo> brokerInfoAsync();
}