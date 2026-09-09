package org.hongxi.jaws.transport;

import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe buffered bridge that implements both {@link StreamObserver}
 * (receiving side) and {@link StreamSource} (sending side).
 * <p>
 * Items produced via {@link #onNext} are buffered until a consumer subscribes
 * via {@link #subscribe}, which must be able to receive everything produced
 * before it attached: in the RPC flow the producer (a business method, or an
 * inbound DATA frame decoded on the event loop) starts emitting before the
 * framework has finished wiring the consumer.
 * <p>
 * Design:
 * <ul>
 *   <li><b>One drainer at a time</b> — every signal funnels into
 *       {@link #drain()}, and only the thread that holds the drain right
 *       delivers. This is what keeps a producer running on the event loop and
 *       a consumer being attached on the business executor from delivering
 *       through two paths at once.</li>
 *   <li><b>Delivery outside the lock</b> — consumer callbacks are never
 *       invoked while holding this instance's monitor, so a slow consumer
 *       cannot block producers.</li>
 *   <li><b>Terminal ordering</b> — {@link #onCompleted} / {@link #onError}
 *       are delivered strictly after every item that preceded them, including
 *       items or terminals that arrive while a replay is in flight. A stream
 *       that emits {@code a}, {@code b} and then completes can never be seen
 *       by the consumer as {@code a}, terminal, {@code b}.</li>
 *   <li><b>No backpressure</b> — RPC streaming items are typically small
 *       messages; buffering them unbounded until the consumer drains them is
 *       an accepted trade-off of dropping {@code Flow.Subscription.request(n)}.</li>
 * </ul>
 * <p>
 * Re-entrancy: a consumer that touches this subject from inside
 * {@code onNext} (closing the stream while consuming an item, or pushing the
 * next one) is handled by the loop — the nested call enqueues and lets the
 * running drainer pick it up.
 * <p>
 * Thread-safety: all mutable state except {@code onCancel} is guarded by
 * {@code synchronized} on this instance. {@code observer} is single-subscriber:
 * the framework attaches exactly one consumer per stream.
 *
 * @param <T> the type of items
 * @author shenhongxi
 */
public class StreamSubject<T> implements StreamObserver<T>, StreamSource<T> {

    private final List<T> items = new ArrayList<>();
    private StreamObserver<? super T> observer;
    private boolean completed;
    private Throwable error;

    /** True while some thread owns the delivery loop. */
    private boolean draining;
    /** True once the terminal signal has been handed to the consumer. */
    private boolean terminalDelivered;

    /**
     * Optional action invoked when the consumer cancels (calls
     * {@link CancelableObserver#cancel}). Set via {@link #setOnCancel}.
     */
    private final AtomicReference<Runnable> onCancel = new AtomicReference<>();

    @Override
    public void onNext(T item) {
        synchronized (this) {
            if (completed || error != null) {
                return;
            }
            items.add(item);
        }
        drain();
    }

    @Override
    public void onError(Throwable t) {
        synchronized (this) {
            if (completed || error != null) {
                return;
            }
            error = t;
        }
        drain();
    }

    @Override
    public void onCompleted() {
        synchronized (this) {
            if (completed || error != null) {
                return;
            }
            completed = true;
        }
        drain();
    }

    @Override
    public void subscribe(StreamObserver<? super T> observer) {
        Objects.requireNonNull(observer, "observer must not be null");
        synchronized (this) {
            this.observer = observer;
        }
        // A drainer already in flight re-reads the state on each round, so it
        // will see this observer; otherwise this call starts the loop.
        drain();
    }

    /**
     * Deliver buffered items — and then the terminal signal, if the stream has
     * ended — using a single-owner loop. Returns without doing anything when
     * another thread already holds the drain right, when no consumer is
     * attached yet, or when everything has been delivered.
     */
    private void drain() {
        synchronized (this) {
            if (draining || observer == null || terminalDelivered) {
                return;
            }
            draining = true;
        }

        try {
            while (true) {
                List<T> batch;
                StreamObserver<? super T> consumer;
                boolean terminal;
                Throwable terminalError;

                synchronized (this) {
                    consumer = observer;
                    if (!items.isEmpty()) {
                        batch = new ArrayList<>(items);
                        items.clear();
                        terminal = false;
                        terminalError = null;
                    } else if (error != null || completed) {
                        // Claim the terminal signal while still holding the
                        // monitor, so no other thread can deliver a second one.
                        batch = List.of();
                        terminal = true;
                        terminalError = error;
                        terminalDelivered = true;
                        draining = false;
                    } else {
                        // Nothing left to hand over right now. Clearing the flag
                        // inside the monitor means a producer that adds an item
                        // after this point is guaranteed to start its own drain.
                        batch = null;
                        terminal = false;
                        terminalError = null;
                        draining = false;
                        return;
                    }
                }

                for (T item : batch) {
                    consumer.onNext(item);
                }

                if (terminal) {
                    if (terminalError != null) {
                        consumer.onError(terminalError);
                    } else {
                        consumer.onCompleted();
                    }
                    return;
                }
            }
        } catch (RuntimeException | Error e) {
            synchronized (this) {
                draining = false;
            }
            throw e;
        }
    }

    /**
     * Set the action to run when the consumer cancels the stream.
     * Must be called before the stream can be canceled.
     */
    public void setOnCancel(Runnable action) {
        this.onCancel.set(action);
    }

    /**
     * @return a {@link CancelableObserver} that wraps this instance's
     *         consumer side, allowing the framework to propagate cancel.
     */
    CancelableObserver cancelableObserver() {
        return new CancelableObserver();
    }

    /**
     * A {@link StreamObserver} wrapper that supports cancellation. When
     * {@link #cancel()} is called, the registered onCancel action runs
     * (e.g. sending RST_STREAM to the remote peer).
     */
    class CancelableObserver implements StreamObserver<T> {
        @Override
        public void onNext(T item) {
            StreamSubject.this.onNext(item);
        }

        @Override
        public void onError(Throwable t) {
            StreamSubject.this.onError(t);
        }

        @Override
        public void onCompleted() {
            StreamSubject.this.onCompleted();
        }

        void cancel() {
            Runnable action = onCancel.getAndSet(null);
            if (action != null) {
                try {
                    action.run();
                } catch (Exception e) {
                    // Cancellation is best-effort
                }
            }
        }
    }
}
