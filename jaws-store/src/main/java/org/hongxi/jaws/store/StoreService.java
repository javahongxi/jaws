package org.hongxi.jaws.store;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The store's RPC contract, carried by the jaws protocol. Every method is
 * future-based end to end: the provider never blocks a business thread, and
 * the long-polling pull may stay pending for seconds — a load shape ordinary
 * RPC benchmarks never exercise.
 *
 * <p>The two offset methods carry a group's consumption progress. Unlike the
 * pull they never suspend: a map lookup and one merge, answered inline the way
 * the admin queries are.
 *
 * <p>Client-side send semantics (sync/async/oneway) are layered on top by
 * StoreProducer; the wire contract stays uniformly async.
 */
public interface StoreService {

    /** Append one message; completes after the configured durability barrier. */
    CompletableFuture<PutResult> putAsync(StoreMessage message);

    /**
     * Append a batch of messages in one RPC. Results come back in input order.
     * This is batching, not group commit: each record is appended on its own,
     * but the whole batch shares one wire frame and (under sync flush) one
     * fsync barrier — a single durability round-trip amortized across the batch.
     */
    CompletableFuture<List<PutResult>> putBatchAsync(List<StoreMessage> messages);

    /**
     * Pull up to maxCount messages from offset. When nothing is new the
     * request is held for up to suspendTimeoutMillis (long polling);
     * 0 disables suspension and returns immediately.
     */
    CompletableFuture<PullResult> pullAsync(String topic, int queueId, long offset,
                                            int maxCount, long suspendTimeoutMillis);

    /** Report the stable broker identity; consumers key offsets by it. */
    CompletableFuture<BrokerInfo> brokerInfoAsync();

    /**
     * Read this broker's stored offset for group@topic@queueId. Absence is
     * reported as {@code notFound()} rather than 0, because 0 is a real
     * position meaning nothing has been consumed yet.
     */
    CompletableFuture<OffsetResult> queryOffsetAsync(String group, String topic, int queueId);

    /**
     * Record a group's progress on this broker. {@code increaseOnly} keeps the
     * record monotonic — a lagging writer cannot rewind a group that already
     * moved ahead — while {@code false} overwrites it, for a deliberate reset
     * after an offset the log rejected. The response holds the authoritative
     * offset after the update, which is the caller's value only when it won.
     */
    CompletableFuture<OffsetResult> commitOffsetAsync(String group, String topic, int queueId,
                                                     long offset, boolean increaseOnly);
}