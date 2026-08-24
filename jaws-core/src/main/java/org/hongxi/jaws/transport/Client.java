package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;

import java.util.concurrent.Flow;

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

    /**
     * Open a server-streaming request and return a {@link Flow.Publisher} that emits
     * each response item as it arrives.  Only supported by transports that
     * implement streaming (e.g. HTTP/2).
     *
     * @param request the RPC request to send
     * @return a publisher emitting streamed response items
     * @throws UnsupportedOperationException if the transport does not support streaming
     */
    default Flow.Publisher<Object> requestStream(Request request) {
        throw new UnsupportedOperationException("Streaming not supported by this transport");
    }
}
