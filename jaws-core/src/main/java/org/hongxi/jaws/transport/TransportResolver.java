package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;

import java.util.Set;

/**
 * Resolves and validates the protocol × transport combination at assembly time.
 * <p>
 * The transport for a protocol is chosen in this order:
 * <ol>
 *   <li>The explicit {@code transportFactory} URL parameter, if present</li>
 *   <li>Fallback: the transport SPI registered under the protocol's own name
 *       (e.g. protocol {@code wire} defaults to transport {@code wire}),
 *       when such a transport exists</li>
 *   <li>Otherwise the {@code transportFactory} default value ({@code netty})</li>
 * </ol>
 * <p>
 * When the chosen transport declares {@link TransportFactory#supportedProtocols()},
 * the protocol must be in that set — otherwise assembly fails fast with a clear
 * message instead of the protocol silently dying on an incompatible wire format
 * at runtime (e.g. gRPC frames fed into the jaws binary codec).
 *
 * @author shenhongxi
 */
public final class TransportResolver {

    private TransportResolver() {
    }

    /**
     * Resolve the transport factory for the given URL, applying the
     * protocol-name fallback and validating compatibility.
     *
     * @param url the service URL (its protocol field is the RPC protocol name)
     * @return the validated transport factory
     * @throws JawsFrameworkException if the configured combination is invalid
     */
    public static TransportFactory resolve(URL url) {
        String configured = url.getParameter("transportFactory");
        boolean explicit = configured != null && !configured.isEmpty();

        ExtensionLoader<TransportFactory> loader =
                ExtensionLoader.getExtensionLoader(TransportFactory.class);

        String transportName = configured;
        if (!explicit) {
            // Fallback: a transport registered under the protocol's own name
            // (wire protocol → wire transport) wins over the generic default.
            String protocolName = url.getProtocol();
            if (protocolName != null && loader.getExtension(protocolName) != null) {
                transportName = protocolName;
            } else {
                transportName = UrlParam.Transport.TRANSPORT_FACTORY.value();
            }
        }

        TransportFactory factory = loader.getExtension(transportName);
        if (factory == null) {
            throw new JawsFrameworkException("No transport factory named '" + transportName
                    + "', url=" + url.getUri());
        }

        Set<String> supported = factory.supportedProtocols();
        if (supported != null && !supported.contains(url.getProtocol())) {
            throw new JawsFrameworkException("Protocol '" + url.getProtocol()
                    + "' cannot run on transport '" + transportName
                    + "' (supported: " + supported + "), url=" + url.getUri());
        }
        return factory;
    }
}
