package org.hongxi.jaws.transport.grpc;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.AbstractTransportFactory;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.Server;

/**
 * {@link org.hongxi.jaws.transport.TransportFactory} implementation backed by gRPC/HTTP2.
 * <p>
 * Select this transport by setting {@code transportFactory=grpc} in the
 * {@link org.hongxi.jaws.config.ProtocolConfig} or URL parameters.
 * <p>
 * gRPC provides HTTP2-based multiplexing, flow control, and lays the groundwork
 * for server-streaming and bidirectional-streaming in Phase 2.
 *
 * @author shenhongxi
 */
@SpiMeta(name = "grpc")
public class GrpcTransportFactory extends AbstractTransportFactory {

    @Override
    protected Server innerCreateServer(URL url, MessageHandler messageHandler) {
        return new GrpcServer(url, messageHandler);
    }

    @Override
    protected Client innerCreateClient(URL url) {
        return new GrpcClient(url);
    }
}
