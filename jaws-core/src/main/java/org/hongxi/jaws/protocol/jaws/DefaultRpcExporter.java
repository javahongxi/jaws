package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.ProviderMessageRouter;
import org.hongxi.jaws.transport.Server;
import org.hongxi.jaws.transport.TransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class DefaultRpcExporter<T> extends AbstractExporter<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultRpcExporter.class);

    private static final ConcurrentMap<String, ProviderMessageRouter> messageRouterMap = new ConcurrentHashMap<>();

    protected Server server;

    public DefaultRpcExporter(Provider<T> provider, URL url) {
        super(provider, url);

        ProviderMessageRouter messageRouter = messageRouterMap.computeIfAbsent(
                url.getHostPort(), key -> new ProviderMessageRouter(url));
        messageRouter.addProvider(provider);

        server = ExtensionLoader.getExtensionLoader(TransportFactory.class)
                .getExtension(url.getParameter(URLParamType.transportFactory))
                .createServer(url, messageRouter);
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
        ProviderMessageRouter requestRouter = messageRouterMap.get(url.getHostPort());
        if (requestRouter != null) {
            requestRouter.removeProvider(provider);
        }
        log.info("DefaultRpcExporter destroy Success: url={}", url);
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