package org.hongxi.jaws.transport;

/**
 * A server-side channel that accepts incoming connections from clients.
 * <p>
 * In addition to the base {@link Channel} capabilities, a server can control
 * request acceptance and participate in graceful shutdown.
 *
 * @see Channel
 * @see Client
 */
public interface Server extends Channel {

    /**
     * Stop accepting new connections/requests. Existing connections and in-flight
     * requests are allowed to complete.
     */
    default void stopAccept() {
        // no-op by default
    }

    /**
     * Wait for in-flight requests to complete within the given timeout.
     *
     * @param timeoutMs max wait time in milliseconds
     */
    default void drainInflightRequests(long timeoutMs) {
        // no-op by default
    }
}
