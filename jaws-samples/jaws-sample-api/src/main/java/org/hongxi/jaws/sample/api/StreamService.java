package org.hongxi.jaws.sample.api;

import java.util.concurrent.Flow;

/**
 * Streaming service interface - demonstrates server-streaming RPC.
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
}
