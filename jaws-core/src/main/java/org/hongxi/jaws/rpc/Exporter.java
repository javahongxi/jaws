package org.hongxi.jaws.rpc;

/**
 * Created by shenhongxi on 2021/3/6.
 */
public interface Exporter<T> extends Node {

    Provider<T> getProvider();

    void unexport();

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
    default void awaitInactiveRequests(long timeout) {
        // no-op by default
    }
}