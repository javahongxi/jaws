package org.hongxi.jaws.transport;

/**
 * Callback interface for handling messages received on a {@link Channel}.
 * <p>
 * When a server receives a request, it delegates processing to the configured
 * message handler. The typical implementation is a router that dispatches
 * requests to the appropriate service provider.
 */
public interface MessageHandler {

    /**
     * Handle a message received from the given channel.
     *
     * @param channel the channel on which the message was received
     * @param message the received message object
     * @return the result of handling the message
     */
    Object handle(Channel channel, Object message);
}
