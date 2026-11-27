package org.hongxi.jaws.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Broker-side exclusive consumer leases: group@topic@queueId to the one
 * consumer allowed to pull that queue and commit its progress. This is what
 * turns the Exclusive subscription type from a usage convention into something
 * the server enforces — mirroring the lease side of Pulsar's subscription
 * definition, where a second consumer attaching to an occupied Exclusive
 * subscription is an error, and RocketMQ's Pop-revive lease rather than its
 * broker, which pairs a group with no consumer identity at all.
 *
 * <p>The lease answers "who may act on this queue", not "who is alive": claim
 * grants it, renew extends it, release drops it, and a holder that stops
 * renewing loses it at its deadline so the queue becomes takeable — that is the
 * difference between a dead consumer and a merely new one, which no identity
 * alone can give. Pulls and offset commits outside the lease are refused, so a
 * consumer that lost its lease (crashed, partitioned, broker restarted) can
 * never advance the group from an in-flight batch after another has taken over.
 *
 * <p>Deliberately in memory only, unlike {@link ConsumerOffsetStore}: a lease
 * asserts a live connection, and no record survives the broker's restart — the
 * right answer, since the holder's socket did not either. Persistence here
 * would resurrect grants to consumers that no longer exist, and the fencing on
 * the pull path would then admit a zombie.
 *
 * <p>Entries are dropped lazily, at the moment some caller needs to know
 * whether the queue is free; the scan thread exists only to make expiry
 * observable in the log and to keep the table from holding dead entries while
 * nobody touches a queue.
 */
public class ConsumerLeaseStore {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerLeaseStore.class);

    /** Holder plus its deadline; a lease is never held past {@code deadlineMillis}. */
    private static final class Lease {
        final String consumerId;
        final long leaseMillis;
        volatile long deadlineMillis;

        Lease(String consumerId, long leaseMillis, long deadlineMillis) {
            this.consumerId = consumerId;
            this.leaseMillis = leaseMillis;
            this.deadlineMillis = deadlineMillis;
        }
    }

    private final StoreConfig config;
    private final ConcurrentMap<String, Lease> leases = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private Thread scanThread;

    public ConsumerLeaseStore(StoreConfig config) {
        this.config = config;
    }

    /** Start the expiry ticker; grants need no thread of their own. */
    public void start() {
        if (config.getLeaseScanIntervalMillis() <= 0) {
            return;
        }
        scanThread = new Thread(this::scanLoop, "StoreLeaseScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    /**
     * Ask for the queue. Granted when nobody holds it, when the holder's lease
     * has expired, or when the caller already holds it (idempotent, so a
     * discovery round re-attaching the same consumer never fights itself);
     * denied while a live lease is held by someone else.
     *
     * <p>Takeover of an expired lease is a compare-and-set on the entry rather
     * than a check-then-act, so two consumers racing an expired lease exactly
     * one of them wins it.
     */
    public LeaseResult claim(String group, String topic, int queueId, String consumerId, long leaseMillis) {
        String key = key(group, topic, queueId);
        long now = System.currentTimeMillis();
        long granted = Math.max(1, leaseMillis);
        Lease existing = leases.compute(key, (k, current) -> {
            if (current == null || current.deadlineMillis <= now || current.consumerId.equals(consumerId)) {
                return new Lease(consumerId, granted, now + granted);
            }
            return current;
        });
        if (existing.consumerId.equals(consumerId)) {
            return LeaseResult.granted();
        }
        return LeaseResult.denied(existing.consumerId);
    }

    /**
     * Extend the lease, without granting one: renewal is how a holder shows it
     * is still there, and answering a lost lease with a fresh grant would make
     * expiry meaningless.
     *
     * @return granted if this consumer still holds a live lease; denied
     *         otherwise, naming whoever holds it now (null once it has lapsed,
     *         so the caller knows to claim again rather than wait)
     */
    public LeaseResult renew(String group, String topic, int queueId, String consumerId) {
        Lease lease = leases.get(key(group, topic, queueId));
        if (lease == null || !lease.consumerId.equals(consumerId)
                || lease.deadlineMillis <= System.currentTimeMillis()) {
            return LeaseResult.denied(liveHolder(lease));
        }
        lease.deadlineMillis = System.currentTimeMillis() + lease.leaseMillis;
        return LeaseResult.granted();
    }

    /** The lease's holder, or null when there is none or it has already lapsed. */
    private static String liveHolder(Lease lease) {
        if (lease == null || lease.deadlineMillis <= System.currentTimeMillis()) {
            return null;
        }
        return lease.consumerId;
    }

    /**
     * Give the queue up early, the polite half of a consumer shutdown — without
     * it the next instance waits out the full lease. Only the holder may
     * release; a stale release must not free a lease somebody else just won.
     */
    public boolean release(String group, String topic, int queueId, String consumerId) {
        boolean[] released = {false};
        leases.computeIfPresent(key(group, topic, queueId), (k, lease) -> {
            if (lease.consumerId.equals(consumerId)) {
                released[0] = true;
                return null;
            }
            return lease;
        });
        return released[0];
    }

    /** Whether this consumer currently holds the queue's live lease. */
    public boolean isHeld(String group, String topic, int queueId, String consumerId) {
        Lease lease = leases.get(key(group, topic, queueId));
        return lease != null && lease.consumerId.equals(consumerId)
                && lease.deadlineMillis > System.currentTimeMillis();
    }

    /** Live leases currently in the table (observability, tests). */
    public int size() {
        return leases.size();
    }

    /** Drop the ticker; live leases die with the process, there is nothing to persist. */
    public void shutdown() {
        stopped = true;
        if (scanThread != null) {
            scanThread.interrupt();
        }
        leases.clear();
    }

    /**
     * Expiry sweep. Nothing waits on it for correctness — every query above
     * checks the deadline itself — so its only jobs are reclaiming entries of
     * queues nobody touches any more and logging the loss once, since a client
     * only learns about it when its next pull comes back unfenced.
     */
    private void scanLoop() {
        while (!stopped) {
            try {
                Thread.sleep(config.getLeaseScanIntervalMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = System.currentTimeMillis();
            int expired = 0;
            Iterator<Map.Entry<String, Lease>> it = leases.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Lease> entry = it.next();
                if (entry.getValue().deadlineMillis <= now) {
                    it.remove();
                    expired++;
                }
            }
            if (expired > 0) {
                logger.info("{} consumer lease(s) expired, queues released to the next claim", expired);
            }
        }
    }

    private static String key(String group, String topic, int queueId) {
        return group + "@" + topic + "@" + queueId;
    }
}
