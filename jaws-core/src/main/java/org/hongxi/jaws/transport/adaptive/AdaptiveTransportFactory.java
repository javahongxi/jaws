package org.hongxi.jaws.transport.adaptive;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractTransportFactory;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;

import java.util.Set;

/**
 * {@link org.hongxi.jaws.transport.TransportFactory} SPI implementation providing
 * a multi-protocol adaptive server that auto-detects the transport protocol
 * from the first bytes of each TCP connection.
 * <p>
 * Select this transport by setting {@code transportFactory=adaptive} in the
 * {@link org.hongxi.jaws.config.ProtocolConfig} or URL parameters.
 * <p>
 * A single adaptive server can simultaneously serve:
 * <ul>
 *   <li>{@code netty} — TCP + Jaws binary protocol (best performance)</li>
 *   <li>{@code http2} — HTTP/2 + Jaws serialization (multiplexing + streaming)</li>
 *   <li>{@code http} — HTTP/1.1 + JSON (debugging + universal access)</li>
 * </ul>
 * <p>
 * This transport is server-side only. The adaptive transport factory
 * cannot create clients; consumers must choose a concrete transport
 * (e.g. {@code netty}, {@code http2}).
 *
 * @author shenhongxi
 * @see AdaptiveServer
 */
@Extension("adaptive")
public class AdaptiveTransportFactory extends AbstractTransportFactory {

    @Override
    public Set<String> supportedProtocols() {
        return Set.of("jaws");
    }

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new AdaptiveServer(url, messageHandler);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        throw new UnsupportedOperationException("Adaptive transport is server-side only; "
                + "set transportFactory to a concrete transport (e.g. netty, http2) for consumers");
    }
}
