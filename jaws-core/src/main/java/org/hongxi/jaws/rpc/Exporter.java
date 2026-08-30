package org.hongxi.jaws.rpc;

/**
 * An exposed service endpoint that owns a {@link Provider} and makes it reachable
 * under a {@link URL}, roughly corresponding to Dubbo's {@code Exporter}. Besides the
 * endpoint lifecycle, it supports graceful shutdown: {@link #stopAccept()} rejects new
 * requests while {@link #drainInflightRequests(long)} waits for in-flight ones.
 *
 * <p>Created by shenhongxi on 2021/3/6.
 *
 * @see AbstractExporter
 */
public interface Exporter<T> extends Endpoint {

    Provider<T> getProvider();

    /**
     * Stop accepting new requests (e.g., close the server channel so no new connections are accepted).
     * In-flight requests are allowed to complete.
     */
    default void stopAccept() {
        // no-op by default
    }

    /**
     * Wait for in-flight requests to complete within the given timeout.
     *
     * @param timeout max wait time in milliseconds
     */
    default void drainInflightRequests(long timeout) {
        // no-op by default
    }
}