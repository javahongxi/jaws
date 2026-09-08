package org.hongxi.jaws.wire;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WireConnectivityTracker}: state transitions,
 * listener notification, and shutdown semantics.
 *
 * @author shenhongxi
 */
class WireConnectivityTrackerTest {

    @Test
    void initialStateIsIdle() {
        WireConnectivityTracker tracker = new WireConnectivityTracker();
        assertEquals(WireConnectivityState.IDLE, tracker.getState());
        assertFalse(tracker.isReady());
        assertFalse(tracker.isShutdown());
    }

    @Test
    void transitionNotifiesListeners() {
        WireConnectivityTracker tracker = new WireConnectivityTracker();
        List<String> transitions = new ArrayList<>();

        tracker.addListener((prev, curr) -> transitions.add(prev + "→" + curr));

        tracker.transitionTo(WireConnectivityState.CONNECTING);
        tracker.transitionTo(WireConnectivityState.READY);

        assertEquals(2, transitions.size());
        assertEquals("IDLE→CONNECTING", transitions.get(0));
        assertEquals("CONNECTING→READY", transitions.get(1));
        assertTrue(tracker.isReady());
    }

    @Test
    void sameStateTransitionReturnsFalse() {
        WireConnectivityTracker tracker = new WireConnectivityTracker();
        tracker.transitionTo(WireConnectivityState.READY);

        // Same state: no notification
        AtomicInteger count = new AtomicInteger();
        tracker.addListener((prev, curr) -> count.incrementAndGet());

        assertFalse(tracker.transitionTo(WireConnectivityState.READY));
        assertEquals(0, count.get());
    }

    @Test
    void shutdownClearsListeners() {
        WireConnectivityTracker tracker = new WireConnectivityTracker();
        AtomicInteger count = new AtomicInteger();
        tracker.addListener((prev, curr) -> count.incrementAndGet());

        tracker.shutdown();
        assertTrue(tracker.isShutdown());
        assertEquals(1, count.get()); // shutdown notification

        // After shutdown, further transitions don't notify (listeners cleared)
        tracker.transitionTo(WireConnectivityState.READY);
        assertEquals(1, count.get()); // no new notification
    }

    @Test
    void transientFailureCycle() {
        WireConnectivityTracker tracker = new WireConnectivityTracker();
        List<String> transitions = new ArrayList<>();
        tracker.addListener((prev, curr) -> transitions.add(prev + "→" + curr));

        tracker.transitionTo(WireConnectivityState.CONNECTING);
        tracker.transitionTo(WireConnectivityState.TRANSIENT_FAILURE);
        tracker.transitionTo(WireConnectivityState.CONNECTING);
        tracker.transitionTo(WireConnectivityState.READY);

        assertEquals(4, transitions.size());
        assertEquals("TRANSIENT_FAILURE→CONNECTING", transitions.get(2));
        assertEquals("CONNECTING→READY", transitions.get(3));
        assertTrue(tracker.isReady());
    }

    @Test
    void removeListenerStopsNotification() {
        WireConnectivityTracker tracker = new WireConnectivityTracker();
        AtomicInteger count = new AtomicInteger();
        WireConnectivityTracker.Listener listener = (prev, curr) -> count.incrementAndGet();

        tracker.addListener(listener);
        tracker.transitionTo(WireConnectivityState.READY);
        assertEquals(1, count.get());

        tracker.removeListener(listener);
        tracker.transitionTo(WireConnectivityState.SHUTDOWN);
        assertEquals(1, count.get()); // no further notification
    }
}
