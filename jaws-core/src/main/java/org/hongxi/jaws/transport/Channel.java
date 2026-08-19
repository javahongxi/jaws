package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;

/**
 * Represents a network communication channel between two endpoints.
 * <p>
 * A channel provides the ability to send requests and manage the connection
 * lifecycle (open/close). Both {@link Server}
 * and {@link Client} extend this interface.
 *
 * @see Server
 * @see Client
 */
public interface Channel {

    /**
     * Send a request through this channel and return the response.
     *
     * @param request the RPC request to send
     * @return the response from the remote side
     */
    Response request(Request request);

    /**
     * Open this channel, establishing the underlying network connection.
     *
     * @return true if the channel was opened successfully
     */
    boolean open();

    /**
     * Close this channel immediately, releasing all associated resources.
     */
    void close();

    /**
     * Close this channel gracefully within the given timeout.
     *
     * @param timeout the maximum time in milliseconds to wait for a graceful close
     */
    void close(int timeout);

    /**
     * Check whether this channel is available for sending requests.
     *
     * @return true if the channel is open and ready
     */
    boolean isAvailable();

    /**
     * Get the URL associated with this channel, containing transport parameters.
     *
     * @return the channel URL
     */
    URL getUrl();
}
