package org.hongxi.jaws.store;

import java.util.concurrent.CompletableFuture;

/**
 * Operational query contract, exported on the same port as {@link StoreService}
 * and distinguished from it only by interface name in the service key.
 *
 * <p>Kept separate from the data-path contract on purpose: pull/put are the hot
 * path every client hits, while this is a low-frequency admin lookup that a
 * data client never binds. Splitting them keeps {@link StoreService} minimal and
 * lets an admin tool hold a reference to just the query surface.
 */
public interface StoreAdminService {

    /**
     * Read one message directly by its physical CommitLog offset — the same way
     * RocketMQ's VIEW_MESSAGE_BY_ID decodes the offset embedded in a msgId and
     * reads it straight from the store, no index involved. A msgId here is just
     * this offset, stable within one broker's identity (see BrokerInfo).
     *
     * <p>Resolves to NOT_FOUND when no valid record starts at the offset: past
     * the log, inside a rolled-file tail hole, or a bad magic.
     */
    CompletableFuture<AdminViewResult> viewMessageAsync(long commitLogOffset);

    /**
     * Snapshot the broker's operating counters — throughput, rejections, the
     * durability frontier and the reput dispatch lag. A low-frequency read
     * intended for an operator or a stress harness; taking it never walks the
     * log, so it does not perturb the measurements it reports.
     */
    CompletableFuture<StatsResult> statsAsync();
}
