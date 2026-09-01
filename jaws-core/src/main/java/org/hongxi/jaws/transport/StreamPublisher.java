package org.hongxi.jaws.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A buffered {@link Flow.Publisher} that bridges transport-level streaming
 * responses to the Java {@link Flow} API.
 * <p>
 * Items are produced by the Netty event loop (via {@link #addItem(Object)})
 * and consumed by the application subscriber. The publisher buffers all items
 * internally; every subscriber receives the full stream from the beginning —
 * including items that were buffered before it subscribed (the application
 * subscribes only after the RPC call returns, so early items commonly race
 * ahead of subscription).
 * <p>
 * Thread-safety: all mutable state is guarded by {@code synchronized} blocks
 * on this instance, except {@code onCancel} which uses an {@link AtomicReference}
 * for lock-free at-most-once cancel semantics. A {@code draining} flag ensures
 * that at most one thread delivers items at any time. When {@link #addItem}
 * is called from the Netty event loop, it triggers {@code drain()} on each
 * subscriber; if a drain is already in progress (e.g. from {@code request()}),
 * the outer loop re-checks for newly arrived items to guarantee nothing is missed.
 *
 * @author shenhongxi
 */
public class StreamPublisher implements Flow.Publisher<Object> {

    private final List<Object> items = new ArrayList<>();
    private boolean completed;
    private Throwable error;
    private final List<Subscription> subscribers = new ArrayList<>();

    /**
     * Invoked once when a subscriber cancels the stream; wired by the client
     * to send RST_STREAM so the server stops producing. Set to null after
     * first execution via {@link AtomicReference#getAndSet} to guarantee
     * at-most-once semantics across multiple subscribers.
     */
    private final AtomicReference<Runnable> onCancel = new AtomicReference<>();

    /**
     * Called from the Netty event loop to add a decoded response item.
     */
    public synchronized void addItem(Object item) {
        if (completed || error != null) return;
        items.add(item);
        for (Subscription sub : subscribers) {
            sub.drain();
        }
    }

    /**
     * Called when the stream completes normally (END_STREAM or trailers received).
     */
    public synchronized void complete() {
        if (completed) return;
        completed = true;
        for (Subscription sub : subscribers) {
            sub.drain();
        }
    }

    /**
     * Called when an error occurs (stream reset, decode failure, etc.).
     */
    public synchronized void completeExceptionally(Throwable t) {
        if (completed || error != null) return;
        error = t;
        for (Subscription sub : subscribers) {
            sub.drain();
        }
    }

    /**
     * Set the action to run when the stream is canceled by the application.
     * Must be called before the stream can be canceled (right after the
     * RPC is issued).
     */
    public void setOnCancel(Runnable action) {
        this.onCancel.set(action);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Object> subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("subscriber must not be null");
        }
        // A subscriber must receive the full stream from the beginning,
        // including items that arrived (and were buffered) before it
        // subscribed. In the RPC flow the application subscribes only
        // after requestStream() returns, so the first items typically
        // race ahead of subscription.
        Subscription sub = new Subscription(subscriber);
        synchronized (this) {
            subscribers.add(sub);
        }
        subscriber.onSubscribe(sub);
    }

    /**
     * Per-subscriber state tracking the next read position in the shared
     * items list. Uses a {@code draining} flag to ensure that at most one
     * thread delivers items at any time, while guaranteeing that no items
     * are missed when {@link #addItem} and {@link #request} interleave.
     */
    private class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super Object> subscriber;
        private int nextIndex;
        private boolean draining;
        private boolean canceled;
        private boolean terminated;

        Subscription(Flow.Subscriber<? super Object> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            drain();
        }

        @Override
        public void cancel() {
            canceled = true;
            // Notify the transport once: the caller no longer wants the
            // stream (sends RST_STREAM so the server stops work)
            Runnable action = onCancel.getAndSet(null);
            if (action == null) {
                return;
            }
            try {
                action.run();
            } catch (Exception e) {
                // Cancellation is best-effort; never surface to the subscriber
            }
        }

        /**
         * Drain all available items to the subscriber. Guarded by the
         * outer instance's monitor; the {@code draining} flag prevents
         * re-entrant calls when {@link #addItem} triggers drain from
         * within an {@code onNext} callback.
         */
        void drain() {
            while (true) {
                synchronized (StreamPublisher.this) {
                    if (draining || canceled || terminated) return;
                    draining = true;
                }
                try {
                    while (!canceled) {
                        Object item;
                        Throwable err;
                        synchronized (StreamPublisher.this) {
                            if (nextIndex < items.size()) {
                                item = items.get(nextIndex++);
                                err = null;
                            } else if (StreamPublisher.this.completed) {
                                item = null;
                                err = null;
                            } else if (error != null) {
                                item = null;
                                err = error;
                            } else {
                                break; // no more items available yet
                            }
                        }
                        if (item != null) {
                            subscriber.onNext(item);
                        } else if (err != null) {
                            terminated = true;
                            subscriber.onError(err);
                            return;
                        } else {
                            // item == null && err == null → stream completed
                            terminated = true;
                            subscriber.onComplete();
                            return;
                        }
                    }
                } finally {
                    synchronized (StreamPublisher.this) {
                        draining = false;
                    }
                }
                // Check if new items / completion arrived while we were draining
                // (they would have seen draining=true and skipped drain)
                synchronized (StreamPublisher.this) {
                    if (canceled || terminated
                            || (nextIndex >= items.size() && error == null)) {
                        return;
                    }
                }
            }
        }
    }
}
