package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Request;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Callback interface for handling messages received by the server.
 * <p>
 * When a server receives a request, it delegates processing to the configured
 * message handler. The typical implementation is a router that dispatches
 * requests to the appropriate service provider.
 * <p>
 * This interface is natively async to avoid blocking transport threads.
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Handle a message asynchronously.
     *
     * @param message the received message object
     * @return a CompletableFuture representing the async processing result
     */
    CompletableFuture<Object> handleAsync(Object message);

    /**
     * Handle a streaming request.  Covers all streaming modes:
     * <ul>
     *   <li>{@code requestStream == null} → server-streaming</li>
     *   <li>{@code requestStream != null} → client-streaming or
     *       bidirectional-streaming</li>
     * </ul>
     * <p>
     * Only provider-side handlers need to override this; client-side handlers
     * never receive streaming requests and can rely on the default
     * {@link UnsupportedOperationException}.
     *
     * @param request       the incoming RPC request
     * @param requestStream a publisher emitting client request items, or
     *                      {@code null} for server-streaming
     * @return a {@link Flow.Publisher} emitting the response items
     */
    default Flow.Publisher<Object> handleStream(Request request, Flow.Publisher<Object> requestStream) {
        throw new UnsupportedOperationException("Streaming not supported by this handler");
    }
}
