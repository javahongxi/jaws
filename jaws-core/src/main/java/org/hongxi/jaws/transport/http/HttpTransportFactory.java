package org.hongxi.jaws.transport.http;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractTransportFactory;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;

import java.util.Set;

/**
 * {@link org.hongxi.jaws.transport.TransportFactory} implementation providing
 * a lightweight HTTP/1.1 + JSON entry point for Jaws RPC services.
 * <p>
 * Select this transport by setting {@code transportFactory=http} in the
 * {@link org.hongxi.jaws.config.ProtocolConfig} or URL parameters.
 * <p>
 * This transport is <b>server-only</b>: it exposes a {@code POST /invoke}
 * endpoint for invoking RPC services with JSON payloads and a {@code GET /health}
 * probe for load-balancer health checks. It does not support outbound client
 * connections — use {@code netty} or {@code http2} for consumer-side transport.
 * <p>
 * Transport matrix:
 * <ul>
 *   <li>{@code netty} — TCP + Jaws binary protocol (best performance)</li>
 *   <li>{@code http2} — HTTP/2 + Jaws binary serialization (multiplexing + streaming)</li>
 *   <li>{@code http} — HTTP/1.1 + JSON (debugging + universal access)</li>
 * </ul>
 *
 * @author shenhongxi
 */
@Extension("http")
public class HttpTransportFactory extends AbstractTransportFactory {

    @Override
    public Set<String> supportedProtocols() {
        return Set.of("jaws");
    }

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new HttpServer(url, messageHandler);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        throw new UnsupportedOperationException(
                "HTTP transport is server-only; use 'netty' or 'http2' for client connections");
    }
}
