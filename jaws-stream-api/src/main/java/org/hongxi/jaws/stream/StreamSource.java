package org.hongxi.jaws.stream;

/**
 * A simple source of streamed items, modeled after gRPC's server-streaming
 * return type. Replaces {@link java.util.concurrent.Flow.Publisher} without
 * the subscription/backpressure machinery.
 * <p>
 * A {@code StreamSource} is typically returned by a server-streaming business
 * method or produced by a transport layer. The framework calls
 * {@link #subscribe(StreamObserver)} to attach a consumer that receives all
 * buffered items from the beginning, including items that were produced
 * before the subscription.
 *
 * @param <T> the type of items emitted
 * @author shenhongxi
 */
public interface StreamSource<T> {

    /**
     * Subscribe a {@link StreamObserver} to receive all items from the
     * beginning. Late subscribers receive the full replay.
     *
     * @param observer the observer to receive items
     */
    void subscribe(StreamObserver<? super T> observer);
}
