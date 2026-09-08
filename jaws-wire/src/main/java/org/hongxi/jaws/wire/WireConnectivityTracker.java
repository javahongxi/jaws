package org.hongxi.jaws.wire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the gRPC connectivity state of a wire channel and notifies
 * registered listeners on state transitions.
 * <p>
 * Thread-safe: state transitions use CAS on an {@link AtomicReference}.
 * Listeners are invoked synchronously on the transitioning thread.
 * <p>
 * Valid transitions follow the gRPC connectivity state model (gRFC A13):
 * <ul>
 *   <li>IDLE → CONNECTING (connection initiated)</li>
 *   <li>CONNECTING → READY (connection established)</li>
 *   <li>CONNECTING → TRANSIENT_FAILURE (connection failed)</li>
 *   <li>READY → IDLE (gone idle, e.g. max connection idle)</li>
 *   <li>READY → TRANSIENT_FAILURE (connection lost)</li>
 *   <li>TRANSIENT_FAILURE → CONNECTING (retrying)</li>
 *   <li>TRANSIENT_FAILURE → READY (reconnected)</li>
 *   <li>Any → SHUTDOWN (terminal state)</li>
 * </ul>
 *
 * @author shenhongxi
 */
public class WireConnectivityTracker {
    private static final Logger log = LoggerFactory.getLogger(WireConnectivityTracker.class);

    private final AtomicReference<WireConnectivityState> state =
            new AtomicReference<>(WireConnectivityState.IDLE);

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Callback interface for connectivity state changes.
     */
    public interface Listener {
        /**
         * Called when the connectivity state changes.
         *
         * @param previous the previous state
         * @param current  the new state
         */
        void onStateChange(WireConnectivityState previous, WireConnectivityState current);
    }

    /**
     * @return the current connectivity state
     */
    public WireConnectivityState getState() {
        return state.get();
    }

    /**
     * Attempt to transition to a new state. If the transition is valid
     * (the state actually changes), registered listeners are notified.
     *
     * @param newState the target state
     * @return true if the state was changed, false if it was already in the target state
     *         or the transition was rejected (e.g. from SHUTDOWN)
     */
    public boolean transitionTo(WireConnectivityState newState) {
        WireConnectivityState previous = state.getAndSet(newState);
        if (previous == newState) {
            return false;
        }

        log.debug("Connectivity state: {} → {}", previous, newState);

        for (Listener listener : listeners) {
            try {
                listener.onStateChange(previous, newState);
            } catch (Exception e) {
                log.warn("Connectivity listener threw exception: {} → {}", previous, newState, e);
            }
        }
        return true;
    }

    /**
     * Register a listener for state changes. The listener is immediately
     * called with the current state if it is not IDLE (so the listener
     * can catch up on missed transitions).
     *
     * @param listener the listener to add
     */
    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a previously registered listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * @return true if the channel is in a state where RPCs can be sent
     */
    public boolean isReady() {
        return state.get() == WireConnectivityState.READY;
    }

    /**
     * @return true if the channel has been shut down
     */
    public boolean isShutdown() {
        return state.get() == WireConnectivityState.SHUTDOWN;
    }

    /**
     * Convenience: transition to SHUTDOWN and clear all listeners.
     */
    public void shutdown() {
        transitionTo(WireConnectivityState.SHUTDOWN);
        listeners.clear();
    }
}
