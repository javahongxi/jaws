package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.TransportResolver;
import org.hongxi.jaws.transport.adaptive.AdaptiveServer;
import org.hongxi.jaws.transport.http.HttpServer;
import org.hongxi.jaws.transport.http.rest.RestAnnotationScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Jaws protocol exporter.
 */
public class JawsExporter<T> extends AbstractExporter<T> {

    private static final Logger log = LoggerFactory.getLogger(JawsExporter.class);

    private static final ConcurrentMap<String, ProviderMessageHandler> messageHandlerMap = new ConcurrentHashMap<>();

    protected Server server;
    private final TransportFactory transportFactory;

    public JawsExporter(Provider<T> provider, URL url) {
        super(provider, url);

        ProviderMessageHandler messageHandler = messageHandlerMap.computeIfAbsent(
                url.getHostPort(), key -> new ProviderMessageHandler());
        messageHandler.addProvider(provider);

        transportFactory = TransportResolver.resolve(url);
        server = transportFactory.createServer(url, messageHandler);

        // Register interface class for JSON argument type conversion (HTTP / adaptive transport)
        if (server instanceof HttpServer httpServer) {
            httpServer.addInterfaceClass(provider.getInterface().getName(), provider.getInterface());
            // Scan for Spring Web / JAX-RS annotations and register REST mappings
            RestAnnotationScanner.scan(provider.getInterface(), provider.getImpl().getClass(),
                    httpServer.getRestMappingRegistry());
            // Auto-register interface methods as MCP tools
            httpServer.getMcpToolRegistry().register(provider);
        } else if (server instanceof AdaptiveServer adaptiveServer) {
            adaptiveServer.addInterfaceClass(provider.getInterface().getName(), provider.getInterface());
            RestAnnotationScanner.scan(provider.getInterface(), provider.getImpl().getClass(),
                    adaptiveServer.getRestMappingRegistry());
            adaptiveServer.getMcpToolRegistry().register(provider);
        }
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
        transportFactory.releaseServer(server);
        log.info("JawsExporter destroy: url={}", url);
    }

    @Override
    public void stopAccept() {
        server.stopAccept();
    }

    @Override
    public void drainInflightRequests(long timeout) {
        server.drainInflightRequests(timeout);
    }
}
