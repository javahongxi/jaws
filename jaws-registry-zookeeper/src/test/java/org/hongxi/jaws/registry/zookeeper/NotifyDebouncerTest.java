package org.hongxi.jaws.registry.zookeeper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link NotifyDebouncer} using an inline fake {@link
 * NotifyDebouncer.Scheduler} so the flush is driven by hand — no threads, no
 * sleeps, no real timers.
 *
 * @author shenhongxi
 */
class NotifyDebouncerTest {

    /** Captures scheduled flushes so the test decides when the window closes. */
    private static final class FakeScheduler implements NotifyDebouncer.Scheduler {
        final List<Runnable> pending = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMs) {
            pending.add(task);
            delays.add(delayMs);
        }

        /** Run the single scheduled flush (asserts there was exactly one). */
        void runAll() {
            List<Runnable> snapshot = new ArrayList<>(pending);
            pending.clear();
            for (Runnable r : snapshot) {
                r.run();
            }
        }
    }

    @Test
    void burstCoalescesToLatestSingleDelivery() {
        FakeScheduler sched = new FakeScheduler();
        List<String> delivered = new ArrayList<>();
        NotifyDebouncer<String> d = new NotifyDebouncer<>(200, sched, delivered::add);

        d.offer("a");
        d.offer("ab");
        d.offer("abc"); // provider list keeps growing within the window

        assertEquals(1, sched.pending.size(), "one burst schedules exactly one flush");
        assertEquals(200L, sched.delays.get(0));
        assertTrue(delivered.isEmpty(), "nothing delivered before the window elapses");

        sched.runAll();
        assertEquals(List.of("abc"), delivered, "only the latest snapshot is delivered");
    }

    @Test
    void subsequentBurstSchedulesANewFlush() {
        FakeScheduler sched = new FakeScheduler();
        List<String> delivered = new ArrayList<>();
        NotifyDebouncer<String> d = new NotifyDebouncer<>(200, sched, delivered::add);

        d.offer("v1");
        sched.runAll();
        assertEquals(0, sched.pending.size(), "flush drains the scheduled task");

        d.offer("v2"); // a later, separate burst
        assertEquals(1, sched.pending.size(), "the second burst armed a fresh flush");
        sched.runAll();

        assertEquals(List.of("v1", "v2"), delivered, "each burst delivers once");
    }

    @Test
    void emptySnapshotAfterDrainDeliversNothing() {
        FakeScheduler sched = new FakeScheduler();
        AtomicInteger count = new AtomicInteger();
        NotifyDebouncer<String> d = new NotifyDebouncer<>(200, sched, s -> count.incrementAndGet());

        d.offer("x");
        sched.runAll();
        sched.runAll(); // a stray/duplicate flush with nothing pending

        assertEquals(1, count.get(), "a flush with no pending snapshot is a no-op");
    }

    @Test
    void zeroDelayDeliversImmediatelyWithoutScheduling() {
        FakeScheduler sched = new FakeScheduler();
        List<String> delivered = new ArrayList<>();
        NotifyDebouncer<String> d = new NotifyDebouncer<>(0, sched, delivered::add);

        d.offer("a");
        d.offer("b");

        assertEquals(List.of("a", "b"), delivered, "delay<=0 delivers every offer synchronously");
        assertTrue(sched.pending.isEmpty(), "nothing scheduled in immediate mode");
    }

    @Test
    void closeDropsPendingDelivery() {
        FakeScheduler sched = new FakeScheduler();
        List<String> delivered = new ArrayList<>();
        NotifyDebouncer<String> d = new NotifyDebouncer<>(200, sched, delivered::add);

        d.offer("stale");
        d.close();
        sched.runAll(); // the already-scheduled flush fires but must be suppressed

        assertTrue(delivered.isEmpty(), "closed debouncer delivers nothing");
        d.offer("after-close");
        assertTrue(delivered.isEmpty(), "offers after close are ignored");
    }
}
