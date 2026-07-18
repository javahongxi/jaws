package org.hongxi.jaws.transport;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Created by shenhongxi on 2020/6/25.
 */
public interface Server extends Endpoint {

    boolean isBound();

    Collection<Channel> getChannels();

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
