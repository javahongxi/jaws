package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.TransportResolver;
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

    public JawsExporter(Provider<T> provider, URL url) {
        super(provider, url);

        ProviderMessageHandler messageHandler = messageHandlerMap.computeIfAbsent(
                url.getHostPort(), key -> new ProviderMessageHandler());
        messageHandler.addProvider(provider);

        server = TransportResolver.resolve(url).createServer(url, messageHandler);
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
        log.info("JawsExporter destroy: url={}", url);
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
