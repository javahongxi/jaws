package org.hongxi.jaws.sample.api;

import java.util.concurrent.Flow;

/**
 * Streaming service interface - demonstrates server-streaming, client-streaming,
 * and bidirectional-streaming RPC.
 * <p>
 * Only works over transports that support streaming (e.g. HTTP/2).
 * Other sample modules are not required to provide an implementation.
 */
public interface StreamService {

    /**
     * Server-streaming: returns a {@link Flow.Publisher} that emits
     * {@code count} greeting items with the given prefix.
     *
     * @param prefix greeting prefix
     * @param count  number of items to stream
     * @return a publisher emitting streaming greeting items
     */
    Flow.Publisher<String> greetStream(String prefix, int count);

    /**
     * Client-streaming: receives a stream of names from the client
     * and returns a single aggregated greeting response.
     * <p>
     * The client sends names via the {@code names} publisher; the server
     * collects all names and returns a single greeting when the stream
     * completes.
     *
     * @param names a publisher emitting client request names
     * @return a single aggregated greeting string
     */
    String collectGreet(Flow.Publisher<String> names);

    /**
     * Bidirectional-streaming: receives a stream of names from the client
     * and returns a stream of greeting responses.
     * <p>
     * The client sends names via the {@code names} publisher; the server
     * echoes each one as a greeting.  When the client completes its stream,
     * the server completes its response stream.
     *
     * @param names a publisher emitting client request names
     * @return a publisher emitting greeting responses
     */
    Flow.Publisher<String> bidiGreet(Flow.Publisher<String> names);
}
