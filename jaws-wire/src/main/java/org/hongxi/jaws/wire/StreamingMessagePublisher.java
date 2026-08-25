package org.hongxi.jaws.wire;

import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * A simple {@link Flow.Publisher} that bridges gRPC server-streaming responses
 * to the Java {@link Flow} API.
 * <p>
 * Items are produced by the Netty event loop (via {@link #addItem(Message)})
 * and consumed by the application subscriber. The publisher buffers all items
 * internally; each subscriber independently tracks its consumption index so
 * that multiple subscribers can iterate the stream at their own pace.
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
class StreamingMessagePublisher implements Flow.Publisher<Message> {

    private final List<Message> items = new ArrayList<>();
    private boolean completed = false;
    private Throwable error = null;
    private final List<StreamingSubscription> subscribers = new ArrayList<>();

    /**
     * Called from the Netty event loop to add a decoded protobuf message.
     */
    synchronized void addItem(Message message) {
        if (completed || error != null) return;
        items.add(message);
        // Trigger drain on all subscribers — items arrived after request() returned
        for (StreamingSubscription sub : subscribers) {
            sub.drain();
        }
    }

    /**
     * Called when the gRPC trailers with {@code grpc-status: OK} are received.
     */
    synchronized void complete() {
        if (completed) return;
        completed = true;
        for (StreamingSubscription sub : subscribers) {
            sub.drain();
        }
    }

    /**
     * Called when a gRPC error status is received or a decode error occurs.
     */
    synchronized void completeExceptionally(Throwable t) {
        if (completed || error != null) return;
        error = t;
        for (StreamingSubscription sub : subscribers) {
            sub.drain();
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Message> subscriber) {
        StreamingSubscription sub;
        synchronized (this) {
            sub = new StreamingSubscription(subscriber, items.size());
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
        private final Flow.Subscriber<? super Message> subscriber;
        private int index;
        private boolean cancelled = false;
        private boolean draining = false;
        private boolean terminalDelivered = false;

        StreamingSubscription(Flow.Subscriber<? super Message> subscriber, int startIndex) {
            this.subscriber = subscriber;
            this.index = startIndex;
        }

        @Override
        public void request(long n) {
            drain();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        /**
         * Drain all available items to the subscriber. Guarded by the
         * outer instance's monitor; the {@code draining} flag prevents
         * re-entrant calls when {@link #addItem} triggers drain from
         * within an {@code onNext} callback.
         */
        void drain() {
            synchronized (StreamingMessagePublisher.this) {
                if (draining || cancelled || terminalDelivered) return;
                draining = true;
            }
            try {
                while (!cancelled) {
                    Message item;
                    boolean isComplete;
                    Throwable err;
                    synchronized (StreamingMessagePublisher.this) {
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
                synchronized (StreamingMessagePublisher.this) {
                    draining = false;
                    // Check if new items / completion arrived while we were draining
                    // (they would have seen draining=true and skipped drain)
                    if (!terminalDelivered && !cancelled
                            && (index < items.size() || completed || error != null)) {
                        drain();
                    }
                }
            }
        }
    }
}
