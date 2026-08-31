package org.hongxi.jaws.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

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
 * on this instance. A {@code draining} flag ensures that at most one thread
 * delivers items at any time. When {@link #addItem} is called from the Netty
 * event loop, it triggers {@code drain()} on each subscriber; if a drain is
 * already in progress (e.g. from {@code request()}), the {@code finally}
 * block re-checks for newly arrived items to guarantee nothing is missed.
 *
 * @author shenhongxi
 */
public class StreamPublisher implements Flow.Publisher<Object> {

    private final List<Object> items = new ArrayList<>();
    private boolean completed;
    private Throwable error;
    private final List<StreamingSubscription> subscribers = new ArrayList<>();

    /**
     * Invoked once when a subscriber cancels the stream; wired by the client
     * to send RST_STREAM so the server stops producing. Runs at most once.
     */
    private volatile Runnable cancelAction;
    private boolean cancelActionFired;

    /**
     * Set the action to run when the stream is canceled by the application.
     * Must be called before the stream can be canceled (right after the
     * RPC is issued).
     */
    public void setCancelAction(Runnable action) {
        this.cancelAction = action;
    }

    /**
     * Called from the Netty event loop to add a decoded response item.
     */
    public synchronized void addItem(Object item) {
        if (completed || error != null) return;
        items.add(item);
        for (StreamingSubscription sub : subscribers) {
            sub.drain();
        }
    }

    /**
     * Called when the stream completes normally (END_STREAM or trailers received).
     */
    public synchronized void complete() {
        if (completed) return;
        completed = true;
        for (StreamingSubscription sub : subscribers) {
            sub.drain();
        }
    }

    /**
     * Called when an error occurs (stream reset, decode failure, etc.).
     */
    public synchronized void completeExceptionally(Throwable t) {
        if (completed || error != null) return;
        error = t;
        for (StreamingSubscription sub : subscribers) {
            sub.drain();
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Object> subscriber) {
        if (subscriber == null) {
            throw new NullPointerException("subscriber must not be null");
        }
        StreamingSubscription sub;
        synchronized (this) {
            // Start from index 0: a subscriber must receive the full stream,
            // including items that arrived (and were buffered) before it
            // subscribed. In the RPC flow the application subscribes only
            // after requestStream() returns, so the first items typically
            // race ahead of subscription.
            sub = new StreamingSubscription(subscriber, 0);
            subscribers.add(sub);
        }
        subscriber.onSubscribe(sub);
    }

    /**
     * Per-subscriber state tracking the consumption index into the shared
     * items list. Uses a {@code draining} flag to ensure that at most one
     * thread delivers items at any time, while guaranteeing that no items
     * are missed when {@link #addItem} and {@link #request} interleave.
     */
    private class StreamingSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super Object> subscriber;
        private int index;
        private boolean canceled = false;
        private boolean draining = false;
        private boolean terminalDelivered = false;

        StreamingSubscription(Flow.Subscriber<? super Object> subscriber, int startIndex) {
            this.subscriber = subscriber;
            this.index = startIndex;
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
            synchronized (StreamPublisher.this) {
                if (cancelActionFired || cancelAction == null) {
                    return;
                }
                cancelActionFired = true;
            }
            try {
                cancelAction.run();
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
            synchronized (StreamPublisher.this) {
                if (draining || canceled || terminalDelivered) return;
                draining = true;
            }
            try {
                while (!canceled) {
                    Object item;
                    boolean isComplete;
                    Throwable err;
                    synchronized (StreamPublisher.this) {
                        if (index < items.size()) {
                            item = items.get(index++);
                            isComplete = false;
                            err = null;
                        } else if (completed) {
                            item = null;
                            isComplete = true;
                            err = null;
                        } else if (error != null) {
                            item = null;
                            isComplete = false;
                            err = error;
                        } else {
                            break; // no more items available yet
                        }
                    }
                    if (item != null) {
                        subscriber.onNext(item);
                    } else if (isComplete) {
                        terminalDelivered = true;
                        subscriber.onComplete();
                        return;
                    } else if (err != null) {
                        terminalDelivered = true;
                        subscriber.onError(err);
                        return;
                    }
                }
            } finally {
                synchronized (StreamPublisher.this) {
                    draining = false;
                    // Check if new items / completion arrived while we were draining
                    // (they would have seen draining=true and skipped drain)
                    if (!terminalDelivered && !canceled
                            && (index < items.size() || completed || error != null)) {
                        drain();
                    }
                }
            }
        }
    }
}
