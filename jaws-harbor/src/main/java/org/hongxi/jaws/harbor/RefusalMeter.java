package org.hongxi.jaws.harbor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how often one refused request type may be logged.
 * <p>
 * A refusal is a boundary statement, and a boundary is worth saying once per
 * window: a client that retries a request type harbor does not implement drives
 * hundreds of lines per minute otherwise — measured with a real Dubbo provider,
 * which treats any refusal as a transient failure and retries on its own
 * schedule. The first sighting still logs at the caller's level; what comes
 * after is counted and folded into the next line, so the volume survives even
 * though the noise does not.
 *
 * @author shenhongxi
 */
final class RefusalMeter {

    /** Default window, matching how often an operator actually looks at logs. */
    static final long DEFAULT_WINDOW_MILLIS = 60_000L;

    private final long windowMillis;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    RefusalMeter() {
        this(DEFAULT_WINDOW_MILLIS);
    }

    RefusalMeter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * Called on every refusal.
     *
     * @return true when the caller should log now — the first time this type is
     *         seen, or once the window has elapsed since the last line
     */
    boolean shouldLog(String type) {
        State state = states.computeIfAbsent(type, key -> new State());
        long now = System.currentTimeMillis();
        synchronized (state) {
            if (!state.seen) {
                state.seen = true;
                state.since = now;
                return true;
            }
            if (now - state.since >= windowMillis) {
                state.since = now;
                return true;
            }
            state.suppressed.incrementAndGet();
            return false;
        }
    }

    /**
     * @return refusals swallowed since the line the caller is about to write
     */
    int suppressedSince(String type) {
        State state = states.get(type);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.suppressed.getAndSet(0);
        }
    }

    /** One mutable bucket per refused type; guarded by its own monitor. */
    private static final class State {
        private boolean seen;
        private long since;
        private final AtomicInteger suppressed = new AtomicInteger();
    }
}
