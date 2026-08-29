package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

import java.util.Set;

/**
 * SPI for creating transport-level {@link Server} and {@link Client} instances.
 * <p>
 * Implementations are responsible for the underlying network transport details
 * (e.g., Netty). Typically used as a singleton shared across the application.
 *
 * @see Server
 * @see Client
 */
@Spi(singleton = true)
public interface TransportFactory {

    /**
     * The RPC protocol names this transport can carry. Used at assembly time
     * to validate the protocol × transport combination and to pick a default
     * transport for a protocol when none is configured explicitly.
     * <p>
     * Returns {@code null} (legacy behavior) to skip validation — transports
     * that predate this method keep working unconditionally.
     *
     * @return the supported protocol names, or null for "any protocol"
     */
    default Set<String> supportedProtocols() {
        return null;
    }

    /**
     * Create a {@link Server} bound to the address specified by the URL.
     * <p>
     * The returned server listens for incoming connections and dispatches
     * received messages to the given {@link MessageHandler}.
     * <p>
     * Implementations may share one server across services exported on the
     * same host:port; each invocation increments the server's reference count.
     * Callers must invoke {@link #releaseServer(Server)} when the server is no
     * longer needed.
     *
     * @param url            the URL containing host, port and transport parameters
     * @param messageHandler the handler that processes incoming messages
     * @return a new or shared server instance
     */
    Server createServer(URL url, MessageHandler messageHandler);

    /**
     * Release a server previously obtained from {@link #createServer(URL, MessageHandler)}.
     * <p>
     * For shared servers the reference count is decremented, and the server
     * is closed only when the last reference is released.
     *
     * @param server the server to release
     */
    void releaseServer(Server server);

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
