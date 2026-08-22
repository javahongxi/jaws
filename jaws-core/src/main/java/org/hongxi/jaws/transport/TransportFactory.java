package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

/**
 * SPI for creating transport-level {@link Server} and {@link Client} instances.
 * <p>
 * Implementations are responsible for the underlying network transport details
 * (e.g., Netty). Typically used as a singleton shared across the application.
 *
 * @see Server
 * @see Client
 */
@Spi(scope = Scope.SINGLETON)
public interface TransportFactory {

    /**
     * Create a {@link Server} bound to the address specified by the URL.
     * <p>
     * The returned server listens for incoming connections and dispatches
     * received messages to the given {@link MessageHandler}.
     *
     * @param url            the URL containing host, port and transport parameters
     * @param messageHandler the handler that processes incoming messages
     * @return a new or shared server instance
     */
    Server createServer(URL url, MessageHandler messageHandler);

    /**
     * Create a {@link Client} that connects to the remote address specified by the URL.
     * <p>
     * Implementations may share one client across services targeting the same
     * remote address; each invocation increments the client's reference count.
     * Callers must invoke {@link #releaseClient(Client)} when the client is no
     * longer needed.
     *
     * @param url the URL containing the remote host, port and transport parameters
     * @return a new or shared client instance
     */
    Client createClient(URL url);

    /**
     * Release a client previously obtained from {@link #createClient(URL)}.
     * <p>
     * For shared clients the reference count is decremented, and the underlying
     * connection is closed only when the last reference is released.
     *
     * @param client the client to release
     */
    void releaseClient(Client client);
}
