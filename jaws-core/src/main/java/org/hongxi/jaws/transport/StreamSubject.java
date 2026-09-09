package org.hongxi.jaws.transport;

import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe buffered bridge that implements both {@link StreamObserver}
 * (receiving side) and {@link StreamSource} (sending side).
 * <p>
 * Items produced via {@link #onNext} are buffered until a consumer subscribes
 * via {@link #subscribe}. Once subscribed, all buffered items are delivered
 * synchronously, and subsequent items are forwarded immediately.
 * <p>
 * This replaces the previous {@code StreamPublisher} which required a complex
 * drain loop with re-check conditions. The simplified design:
 * <ul>
 *   <li><b>No drain loop</b> — items are either forwarded directly (if a
 *       subscriber is attached) or buffered for later replay.</li>
 *   <li><b>No backpressure</b> — RPC streaming items are typically small
 *       protobuf messages; unbounded buffering is acceptable.</li>
 *   <li><b>Terminal signal ordering</b> — {@link #onCompleted}/{@link #onError}
 *       always delivers after all buffered items, because the buffer is
 *       flushed before the terminal signal in both the subscribe and
 *       direct-forward paths.</li>
 * </ul>
 * <p>
 * Thread-safety: all mutable state is guarded by {@code synchronized} on this
 * instance, except {@code onCancel} which uses an {@link AtomicReference} for
 * lock-free at-most-once cancel semantics.
 *
 * @param <T> the type of items
 * @author shenhongxi
 */
public class StreamSubject<T> implements StreamObserver<T>, StreamSource<T> {

    private final List<T> items = new ArrayList<>();
    private StreamObserver<? super T> observer;
    private boolean completed;
    private Throwable error;

    /**
     * Optional action invoked when the consumer cancels (calls
     * {@link CancelableObserver#cancel}). Set via {@link #setOnCancel}.
     */
    private final AtomicReference<Runnable> onCancel = new AtomicReference<>();

    @Override
    public synchronized void onNext(T item) {
        if (completed || error != null) return;
        if (observer != null) {
            observer.onNext(item);
        } else {
            items.add(item);
        }
    }

    @Override
    public synchronized void onError(Throwable t) {
        if (completed || error != null) return;
        error = t;
        if (observer != null) {
            flushAndComplete();
        }
    }

    @Override
    public synchronized void onCompleted() {
        if (completed) return;
        completed = true;
        if (observer != null) {
            flushAndComplete();
        }
    }

    @Override
    public void subscribe(StreamObserver<? super T> observer) {
        if (observer == null) {
            throw new NullPointerException("observer must not be null");
        }
        List<T> buffered;
        Throwable terminalError;
        boolean isCompleted;
        synchronized (this) {
            this.observer = observer;
            buffered = new ArrayList<>(items);
            items.clear();
            terminalError = error;
            isCompleted = completed;
        }
        // Deliver all buffered items first (outside the lock to avoid
        // blocking producers during potentially slow consumer callbacks)
        for (T item : buffered) {
            observer.onNext(item);
        }
        // Then deliver the terminal signal if the stream has ended
        if (terminalError != null) {
            observer.onError(terminalError);
        } else if (isCompleted) {
            observer.onCompleted();
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
     *         subscriber side, allowing the framework to propagate cancel.
     */
    CancelableObserver cancelableObserver() {
        return new CancelableObserver();
    }

    private void flushAndComplete() {
        // Deliver any items that arrived between the last onNext and now
        for (int i = 0; i < items.size(); i++) {
            observer.onNext(items.get(i));
        }
        items.clear();
        if (error != null) {
            observer.onError(error);
        } else {
            observer.onCompleted();
        }
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
