package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

/**
 * A client-side channel that connects to a remote {@link Server}.
 * <p>
 * In addition to the base {@link Channel} capabilities, a client supports
 * sending RPC requests to the remote server.
 *
 * @see Channel
 * @see Server
 */
public interface Client extends Channel {

    /**
     * Send an RPC request to the remote server and return the response.
     *
     * @param request the RPC request to send
     * @return the response from the remote side
     */
    Response request(Request request);
}
