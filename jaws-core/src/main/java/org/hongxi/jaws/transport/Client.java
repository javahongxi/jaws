package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;

/**
 * A client-side channel that connects to a remote {@link Server}.
 * <p>
 * In addition to the base {@link Channel} capabilities, a client supports
 * sending heartbeat requests to keep the connection alive.
 *
 * @see Channel
 * @see Server
 */
public interface Client extends Channel {

    /**
     * Send a heartbeat request to the remote server to keep the connection alive.
     *
     * @param request the heartbeat request
     */
    void heartbeat(Request request);
}
