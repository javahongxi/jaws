package org.hongxi.jaws.stream;

/**
 * A simple observer interface for receiving streamed items, modeled after
 * gRPC's {@code StreamObserver}. Replaces {@link java.util.concurrent.Flow.Subscriber}
 * without the subscription/backpressure machinery — RPC streaming scenarios
 * do not need {@code onSubscribe} or {@code request(n)}.
 * <p>
 * Usage:
 * <ul>
 *   <li><b>Receiving side</b> — the framework or business code implements this
 *       interface to receive items pushed by the producer.</li>
 *   <li><b>Sending side</b> — the framework passes a {@code StreamObserver} to
 *       the business code, which calls {@link #onNext}, {@link #onError}, or
 *       {@link #onCompleted} to emit response items.</li>
 * </ul>
 * <p>
 * Contract:
 * <ul>
 *   <li>{@link #onNext} may be called zero or more times.</li>
 *   <li>After {@link #onCompleted} or {@link #onError}, no further calls are made.</li>
 *   <li>Implementations should be thread-safe if items may arrive from
 *       multiple threads (the buffering implementation provided by
 *       {@code jaws-core} is thread-safe).</li>
 * </ul>
 *
 * @param <T> the type of items observed
 * @author shenhongxi
 */
public interface StreamObserver<T> {

    /**
     * Called when a new item is available.
     *
     * @param item the streamed item
     */
    void onNext(T item);

    /**
     * Called when the stream fails with an error. No further calls will be made.
     *
     * @param t the error
     */
    void onError(Throwable t);

    /**
     * Called when the stream completes normally. No further calls will be made.
     */
    void onCompleted();
}
