package org.hongxi.jaws.rpc;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

/**
 * RPC Protocol extension interface, which encapsulates the details of remote invocation.
 *
 * <p>Conventions:
 * <li>When user invokes the 'call()' method in object that the 'refer()' returns,
 *     the protocol needs to correspondingly execute the 'call()' method of Provider
 *     object that received by 'export()' method.</li>
 * <li>Reference that returned by 'refer()' is implemented by the protocol.
 *     The remote invocation request should be sent by that Reference.</li>
 * <li>The Provider that 'export()' receives will be implemented by framework.
 *     Protocol implementation should not care with that.</li>
 *
 * (SPI, Singleton, ThreadSafe)
 */
@Spi(scope = Scope.SINGLETON)
public interface Protocol {

    /**
     * Export service for remote invocation.
     * The URL is obtained from the provider itself via {@link Provider#getUrl()}.
     *
     * @param provider service provider
     * @param <T>      service type
     * @return exporter for the exported service
     */
    <T> Exporter<T> export(Provider<T> provider);

    /**
     * Refer a remote service.
     *
     * @param interfaceClass service interface class
     * @param url            URL address for the remote service
     * @param <T>            service type
     * @return reference for the remote service
     */
    <T> Reference<T> refer(Class<T> interfaceClass, URL url);

    void destroy();
}