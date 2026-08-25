package org.hongxi.jaws.wire;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.TransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Wire protocol exporter. Creates a {@link WireServer} via the
 * {@link TransportFactory} SPI, wrapping the {@link ProviderMessageHandler}
 * with a {@link WireMessageHandler} to bridge raw protobuf bytes to typed
 * protobuf {@link com.google.protobuf.Message} instances.
 * <p>
 * This enables the full Jaws filter chain and Provider pipeline to work
 * with protobuf-typed arguments while the transport layer speaks gRPC
 * wire format.
 *
 * @author shenhongxi
 */
public class WireExporter<T> extends AbstractExporter<T> {

    private static final Logger log = LoggerFactory.getLogger(WireExporter.class);

    private static final ConcurrentMap<String, ProviderMessageHandler> messageHandlerMap =
            new ConcurrentHashMap<>();

    protected Server server;

    public WireExporter(Provider<T> provider, URL url) {
        super(provider, url);

        ProviderMessageHandler baseHandler = messageHandlerMap.computeIfAbsent(
                url.getHostPort(), key -> new ProviderMessageHandler());
        baseHandler.addProvider(provider);

        // Wrap with WireMessageHandler for byte[] ↔ Message conversion
        WireProtoTypes protoTypes = WireProtoTypes.fromServiceInterface(
                provider.getInterface());
        WireMessageHandler wireHandler = new WireMessageHandler(
                baseHandler, protoTypes);

        server = ExtensionLoader.getExtensionLoader(TransportFactory.class)
                .getExtension(url.getParameter(UrlParam.Transport.TRANSPORT_FACTORY))
                .createServer(url, wireHandler);
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
        ProviderMessageHandler messageHandler = messageHandlerMap.get(url.getHostPort());
        if (messageHandler != null) {
            messageHandler.removeProvider(provider);
        }
        log.info("WireExporter destroy: url={}", url);
    }

    @Override
    public void stopAccept() {
        server.stopAccept();
    }

    @Override
    public void awaitInactiveRequests(long timeout) {
        server.awaitInactiveRequests(timeout);
    }
}
