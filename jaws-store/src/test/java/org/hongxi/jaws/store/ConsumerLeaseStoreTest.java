package org.hongxi.jaws.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the broker-side exclusive lease table, the mechanism that turns
 * Exclusive from a convention into something the server enforces: one live holder
 * per group@topic@queueId, a second claim refused while the lease is held, a lapsed
 * lease takeable by the next consumer, renewal open only to the current holder, and
 * a release honourable only by that holder. Expiry is driven with a short lease
 * rather than a clock seam, since the deadline is checked lazily on every call.
 */
class ConsumerLeaseStoreTest {

    @TempDir
    Path tempDir;

    private ConsumerLeaseStore store() {
        StoreConfig config = new StoreConfig();
        config.setStorePath(tempDir.toString());
        // The sweep is a log-reclamation nicety; every assertion here is driven by
        // the lazy deadline check, so keep the ticker off to avoid timing coupling.
        config.setLeaseScanIntervalMillis(0);
        ConsumerLeaseStore store = new ConsumerLeaseStore(config);
        store.start();
        return store;
    }

    @Test
    void firstClaimWinsAndSecondIsRefusedUntilRelease() {
        ConsumerLeaseStore store = store();
        LeaseResult first = store.claim("g", "t", 0, "c1", 30_000);
        assertTrue(first.isGranted(), "an unheld queue must be claimable");

        LeaseResult second = store.claim("g", "t", 0, "c2", 30_000);
        assertFalse(second.isGranted(), "a live holder blocks a second consumer");
        assertEquals("c1", second.getHolder(), "the rejection names the holder");

        // Same consumer re-claiming is idempotent, so a re-attach never fights itself.
        assertTrue(store.claim("g", "t", 0, "c1", 30_000).isGranted());
        assertTrue(store.isHeld("g", "t", 0, "c1"));
        assertFalse(store.isHeld("g", "t", 0, "c2"), "the loser holds nothing");

        // A different group/topic/queue is an independent lease.
        assertTrue(store.claim("g", "t", 1, "c2", 30_000).isGranted());
        assertTrue(store.claim("g2", "t", 0, "c2", 30_000).isGranted());
    }

    @Test
    void lapsedLeaseBecomesTakeableByAnother() throws InterruptedException {
        ConsumerLeaseStore store = store();
        assertTrue(store.claim("g", "t", 0, "c1", 200).isGranted());
        assertFalse(store.claim("g", "t", 0, "c2", 30_000).isGranted(), "still held while live");

        Thread.sleep(300);
        assertTrue(store.claim("g", "t", 0, "c2", 30_000).isGranted(),
                "once c1 stopped renewing the queue frees for the next consumer");
        assertFalse(store.isHeld("g", "t", 0, "c1"), "the lapsed holder no longer holds it");
    }

    @Test
    void renewExtendsOnlyForTheLiveHolder() throws InterruptedException {
        ConsumerLeaseStore store = store();
        store.claim("g", "t", 0, "c1", 300);

        // A foreign renewal cannot sneak a lease it does not own.
        LeaseResult foreign = store.renew("g", "t", 0, "c2");
        assertFalse(foreign.isGranted());
        assertEquals("c1", foreign.getHolder(), "the stranger is told who holds it");

        // The holder extends its own lease past its original deadline.
        Thread.sleep(200);
        assertTrue(store.renew("g", "t", 0, "c1").isGranted());
        Thread.sleep(200);
        assertTrue(store.isHeld("g", "t", 0, "c1"), "the renewal pushed the deadline out");

        // Once lapsed, renewal does not grant itself back in — it must claim again.
        Thread.sleep(300);
        LeaseResult afterLapse = store.renew("g", "t", 0, "c1");
        assertFalse(afterLapse.isGranted(), "a lapsed holder cannot renew, only re-claim");
        assertNull(afterLapse.getHolder(), "the lease is gone, nobody holds it now");
    }

    @Test
    void onlyTheHolderCanRelease() {
        ConsumerLeaseStore store = store();
        store.claim("g", "t", 0, "c1", 30_000);

        assertFalse(store.release("g", "t", 0, "c2"), "a stranger cannot hand back a lease");
        assertTrue(store.isHeld("g", "t", 0, "c1"), "so the queue stays held");

        assertTrue(store.release("g", "t", 0, "c1"), "the holder gives it up");
        assertFalse(store.isHeld("g", "t", 0, "c1"));
        assertTrue(store.claim("g", "t", 0, "c2", 30_000).isGranted(), "the next consumer gets in");

        // Releasing a lease nobody holds is a no, not an error.
        assertFalse(store.release("g", "never", 0, "c1"));
    }
}
