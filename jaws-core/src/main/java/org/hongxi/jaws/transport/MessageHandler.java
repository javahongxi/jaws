package org.hongxi.jaws.transport;

import java.util.concurrent.CompletableFuture;

/**
 * Callback interface for handling messages received on a {@link Channel}.
 * <p>
 * When a server receives a request, it delegates processing to the configured
 * message handler. The typical implementation is a router that dispatches
 * requests to the appropriate service provider.
 * <p>
 * This interface is natively async to avoid blocking transport threads.
 */
public interface MessageHandler {

    /**
     * Handle a message received from the given channel asynchronously.
     *
     * @param channel the channel on which the message was received
     * @param message the received message object
     * @return a CompletableFuture representing the async processing result
     */
    CompletableFuture<Object> handleAsync(Channel channel, Object message);
}
