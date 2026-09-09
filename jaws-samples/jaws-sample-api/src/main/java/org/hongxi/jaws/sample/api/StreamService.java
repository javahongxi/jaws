package org.hongxi.jaws.sample.api;

import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

/**
 * Streaming service interface - demonstrates server-streaming, client-streaming,
 * and bidirectional-streaming RPC.
 * <p>
 * Only works over transports that support streaming (e.g. HTTP/2).
 * Other sample modules are not required to provide an implementation.
 */
public interface StreamService {

    /**
     * Server-streaming: returns a {@link StreamSource} that emits
     * {@code count} greeting items with the given prefix.
     *
     * @param prefix greeting prefix
     * @param count  number of items to stream
     * @return a source emitting streaming greeting items
     */
    StreamSource<String> greetStream(String prefix, int count);

    /**
     * Client-streaming: receives a stream of names from the client
     * and returns a single aggregated greeting response.
     * <p>
     * The client sends names via the {@code names} observer; the server
     * collects all names and returns a single greeting when the stream
     * completes.
     *
     * @param names an observer receiving client request names
     * @return a single aggregated greeting string
     */
    String collectGreet(StreamObserver<String> names);

    /**
     * Bidirectional-streaming: receives a stream of names from the client
     * and returns a stream of greeting responses.
     * <p>
     * The client sends names via the {@code names} observer; the server
     * echoes each one as a greeting.  When the client completes its stream,
     * the server completes its response stream.
     *
     * @param names an observer receiving client request names
     * @return a source emitting greeting responses
     */
    StreamSource<String> bidiGreet(StreamObserver<String> names);
}
