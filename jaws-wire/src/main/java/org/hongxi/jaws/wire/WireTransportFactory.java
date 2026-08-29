package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractTransportFactory;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;

import java.util.Set;

/**
 * {@link org.hongxi.jaws.transport.TransportFactory} SPI implementation for the
 * wire (gRPC wire format) transport, registered as {@code @Extension("wire")}.
 * <p>
 * <b>Server side</b>: creates a {@link WireServer} in SPI adapter mode, using
 * {@link WireSpiServerStreamHandler} to bridge gRPC calls to the Jaws
 * {@link MessageHandler} pipeline. The gRPC path is parsed to populate
 * interface/method routing, and raw protobuf bytes are passed as the request
 * argument for the Jaws framework to handle.
 * <p>
 * <b>Client side</b>: creates a {@link WireClient} that speaks the gRPC
 * wire format.
 * <p>
 * <b>Note</b>: For full protobuf-typed integration (where request/response are
 * strongly-typed {@link com.google.protobuf.Message} subclasses), use the
 * direct API: {@link WireServer}(URL, {@link WireServiceRegistry}) +
 * {@link WireMethodHandler}.
 *
 * @author shenhongxi
 */
@Extension("wire")
public class WireTransportFactory extends AbstractTransportFactory {

    @Override
    public Set<String> supportedProtocols() {
        // gRPC wire format is bound to HTTP/2 framing by definition
        return Set.of("wire");
    }

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new WireServer(url, messageHandler);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        return new WireClient(url);
    }
}
