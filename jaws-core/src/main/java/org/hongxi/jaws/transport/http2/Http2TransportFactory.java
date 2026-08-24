package org.hongxi.jaws.transport.http2;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractTransportFactory;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;

/**
 * {@link org.hongxi.jaws.transport.TransportFactory} implementation backed by
 * raw Netty HTTP/2 (h2c prior-knowledge), without any gRPC/protobuf dependency.
 * <p>
 * Select this transport by setting {@code transportFactory=http2} in the
 * {@link org.hongxi.jaws.config.ProtocolConfig} or URL parameters.
 * <p>
 * HTTP/2 provides stream multiplexing over a single TCP connection (removing
 * the head-of-line blocking of the request-id multiplexed jaws TCP protocol),
 * built-in flow control, and transparency to L7 infrastructure (gateways,
 * service mesh sidecars). Jaws serialization and the message handler pipeline
 * are fully preserved; only the framing layer changes.
 * <p>
 * Supports both unary and server-streaming invocations.
 *
 * @author shenhongxi
 */
@Extension("http2")
public class Http2TransportFactory extends AbstractTransportFactory {

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new Http2Server(url, messageHandler);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        return new Http2Client(url);
    }
}
