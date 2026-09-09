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
 * <p>
 * <b>Threading contract</b> — callbacks on a business-code observer
 * (e.g. the {@code StreamObserver} passed as a method parameter to receive
 * request items on the provider side) are invoked from <b>multiple
 * threads</b>:
 * <ul>
 *   <li>The <b>first</b> request DATA frame is dispatched to the server's
 *       business executor ({@code serverExecutor}); the business method
 *       itself runs there and may block safely.</li>
 *   <li><b>Subsequent</b> request DATA frames (bidi and client-streaming)
 *       deliver {@code onNext} / {@code onCompleted} on the <b>Netty I/O
 *       thread</b>. Implementations <b>must not block</b>.</li>
 * </ul>
 * Likewise, response-side observers (client receiving server-streamed
 * items) receive {@code onNext} on the <b>Netty I/O thread</b>.
 * <p>
 * In summary: only the initial business-method invocation on the provider
 * side runs on the server executor. All streaming callbacks run on I/O
 * threads and must be non-blocking.
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
