package org.hongxi.jaws.transport;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * A server-side channel that accepts incoming connections from clients.
 * <p>
 * In addition to the base {@link Channel} capabilities, a server can manage
 * multiple client channels, control request acceptance, and participate in
 * graceful shutdown.
 *
 * @see Channel
 * @see Client
 */
public interface Server extends Channel {

    /**
     * Check whether this server is bound to a local address and listening.
     *
     * @return true if the server is bound
     */
    boolean isBound();

    /**
     * Get all active client channels connected to this server.
     *
     * @return an unmodifiable collection of connected channels
     */
    Collection<Channel> getChannels();

    /**
     * Get the channel associated with the given remote address.
     *
     * @param remoteAddress the remote address of the client
     * @return the corresponding channel, or null if not found
     */
    Channel getChannel(InetSocketAddress remoteAddress);

    /**
     * Stop accepting new connections/requests. Existing connections and in-flight
     * requests are allowed to complete.
     */
    default void stopAccept() {
        // no-op by default
    }

    /**
     * Returns the number of currently active (in-flight) requests being processed.
     */
    default int getActiveRequestCount() {
        return 0;
    }

    /**
     * Wait for in-flight requests to complete within the given timeout.
     *
     * @param timeoutMs max wait time in milliseconds
     */
    default void awaitInactiveRequests(long timeoutMs) {
        // no-op by default
    }
}
