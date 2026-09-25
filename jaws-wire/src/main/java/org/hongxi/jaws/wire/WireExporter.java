package org.hongxi.jaws.wire;

import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.TransportResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Wire protocol exporter. Creates a {@link WireServer} via the
 * {@link TransportFactory} SPI, backed by the one {@link WireMessageHandler}
 * that owns this {@code host:port}: that handler converts raw protobuf bytes to
 * typed {@link com.google.protobuf.Message} instances and holds the port's
 * provider registry, so the full Jaws filter chain and Provider pipeline work
 * with protobuf arguments while the transport speaks gRPC wire format.
 *
 * @author shenhongxi
 */
public class WireExporter<T> extends AbstractExporter<T> {

    private static final Logger log = LoggerFactory.getLogger(WireExporter.class);

    /**
     * One handler per {@code host:port}, because a shared server keeps whichever
     * handler it was created with; every service exported on that address
     * registers into the same handler.
     */
    private static final ConcurrentMap<String, WireMessageHandler> handlerMap =
            new ConcurrentHashMap<>();

    protected Server server;
    private final TransportFactory transportFactory;
    private final WireMessageHandler wireHandler;

    public WireExporter(Provider<T> provider, URL url) {
        super(provider, url);

        String hostPort = url.getHostPort();
        WireProtoTypes protoTypes = WireProtoTypes.fromServiceInterface(
                provider.getInterface());
        wireHandler = handlerMap.computeIfAbsent(hostPort, key -> new WireMessageHandler());
        wireHandler.addService(provider, protoTypes);

        // Register the service interface for gRPC server reflection
        WireServer.addProviderServiceInterface(hostPort, provider.getInterface());

        transportFactory = TransportResolver.resolve(url);
        server = transportFactory.createServer(url, wireHandler);
    }

    @Override
    protected boolean doInit() {
        return server.open();
    }

    @Override
    public boolean isAvailable() {
        return server.isAvailable();
    }

    @Override
    public void destroy() {
        String hostPort = url.getHostPort();
        wireHandler.removeService(provider);
        if (wireHandler.isEmpty()) {
            handlerMap.remove(hostPort, wireHandler);
        }
        // Unregister the service interface from gRPC server reflection
        WireServer.removeProviderServiceInterface(hostPort, provider.getInterface());
        transportFactory.releaseServer(server);
        log.info("WireExporter destroy: url={}", url);
    }

    @Override
    public void stopAccept() {
        server.stopAccept();
    }

    @Override
    public void drainInflightRequests(long timeout) {
        server.drainInflightRequests(timeout);
    }

    /**
     * The wire server this exporter registered its service on, exposed for the
     * server-side wiring that has no URL representation: attaching a
     * {@link ServerStreamTracer.Factory}, or replacing the compressor and
     * decompressor registries. Same reason {@link WireServer#getHealthService()}
     * is reachable.
     *
     * @return the shared wire server, or {@code null} before {@link #init()}
     *         has created it
     */
    public WireServer getServer() {
        return (WireServer) server;
    }
}
