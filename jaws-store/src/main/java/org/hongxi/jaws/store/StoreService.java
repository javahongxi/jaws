package org.hongxi.jaws.store;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The store's RPC contract, carried by the jaws protocol. Every method is
 * future-based end to end: the provider never blocks a business thread, and
 * the long-polling pull may stay pending for seconds — a load shape ordinary
 * RPC benchmarks never exercise.
 *
 * <p>The three offset methods never suspend the way a pull does: two carry a
 * group's consumption progress, one reports a queue's tail. Each is a map lookup
 * or an atomic read, answered inline without parking a worker.
 *
 * <p>The three lease methods make Exclusive consumption a server-side invariant:
 * a consumer claims the queue's lease before it can act on it, renews it on a
 * cadence to keep it alive, and releases it on a clean shutdown. A pull and an
 * offset commit both carry the caller's identity and are refused unless it holds
 * that queue's live lease, so a second consumer of the same group is rejected at
 * attach time and a disconnected former holder can never advance the group from
 * an in-flight batch after another has taken over.
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
     *
     * <p>Fenced by the lease: {@code group}+{@code consumerId} must hold this
     * queue's live lease or the answer is {@link PullStatus#NOT_LEASED} — the
     * caller either never attached or has been superseded.
     */
    CompletableFuture<PullResult> pullAsync(String group, String consumerId, String topic, int queueId,
                                           long offset, int maxCount, long suspendTimeoutMillis);

    /**
     * Take the queue's exclusive lease for this consumer. Granted when the queue
     * is free, its current lease has expired, or this consumer already holds it
     * (idempotent re-attach); denied with the live holder otherwise. This is the
     * attach point where a second concurrent consumer of the group is refused.
     */
    CompletableFuture<LeaseResult> claimLeaseAsync(String group, String topic, int queueId,
                                                   String consumerId, long leaseMillis);

    /** Extend this consumer's lease by the duration it was granted. Never grants:
     * a holder whose lease already lapsed must claim again, not renew itself back in. */
    CompletableFuture<LeaseResult> renewLeaseAsync(String group, String topic, int queueId, String consumerId);

    /** Hand the queue back early on a clean shutdown so the next consumer need not
     * wait out the lease. Only the current holder's release takes effect. */
    CompletableFuture<Boolean> releaseLeaseAsync(String group, String topic, int queueId, String consumerId);

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
     *
     * <p>Fenced by the lease like {@link #pullAsync}: a consumer that no longer
     * holds the queue (superseded after a partition) cannot commit progress the
     * new holder has not made, which is what keeps the shared offset trustworthy.
     */
    CompletableFuture<OffsetResult> commitOffsetAsync(String group, String consumerId, String topic, int queueId,
                                                     long offset, boolean increaseOnly);

    /**
     * The queue's tail on this broker — the offset one past the last written
     * record. Queue-scoped rather than group-scoped, and always answerable: a
     * queue nothing has been written to has a tail of 0. A consumer whose group
     * has no stored offset starts here instead of replaying the backlog, which is
     * what RocketMQ's CONSUME_FROM_LAST_OFFSET does with GET_MAX_OFFSET.
     */
    CompletableFuture<Long> maxOffsetAsync(String topic, int queueId);
}