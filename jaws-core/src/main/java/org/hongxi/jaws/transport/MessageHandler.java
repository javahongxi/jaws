package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.Provider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

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

    /**
     * Handle a server-streaming request, returning a {@link Flow.Publisher}
     * that emits stream items.
     * <p>
     * Only provider-side handlers need to override this; client-side handlers
     * never receive streaming requests and can rely on the default
     * {@link UnsupportedOperationException}.
     *
     * @param channel the channel on which the message was received
     * @param message the incoming RPC request
     * @return a {@link Flow.Publisher} emitting the stream items
     */
    default Flow.Publisher<Object> handleStream(Channel channel, Object message) {
        throw new UnsupportedOperationException("Streaming not supported by this handler");
    }

    /**
     * Find a registered provider by its interface name alone, without requiring
     * group or version. Used by protocol adapters (e.g. gRPC compatibility) that
     * do not carry Jaws-specific routing metadata.
     *
     * @param interfaceName the fully-qualified service interface name
     * @return the matching provider, or {@code null} if none is registered
     */
    default Provider<?> findProviderByInterface(String interfaceName) {
        return null;
    }
}
