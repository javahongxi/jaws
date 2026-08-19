package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.ProviderMessageRouter;
import org.hongxi.jaws.transport.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class DefaultRpcExporter<T> extends AbstractExporter<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultRpcExporter.class);

    private static final ConcurrentMap<String, ProviderMessageRouter> IP_PORT_TO_REQUEST_ROUTER = new ConcurrentHashMap<>();

    protected final ConcurrentMap<String, Exporter<?>> exporterMap;
    protected Server server;

    public DefaultRpcExporter(Provider<T> provider, URL url,
                              ConcurrentMap<String, Exporter<?>> exporterMap) {
        super(provider, url);
        this.exporterMap = exporterMap;

        ProviderMessageRouter requestRouter = IP_PORT_TO_REQUEST_ROUTER.computeIfAbsent(
                url.getServerPortStr(), key -> new ProviderMessageRouter(url));
        requestRouter.addProvider(provider);

        server = ExtensionLoader.getExtensionLoader(TransportFactory.class)
                .getExtension(url.getParameter(URLParamType.transportFactory))
                .createServer(url, requestRouter);
    }

    @Override
    protected boolean doInit() {
        return server.open();
    }

    @Override
    public void unexport() {
        String protocolKey = JawsFrameworkUtils.getProtocolKey(url);
        String ipPort = url.getServerPortStr();

        // noinspection unchecked
        Exporter<T> exporter = (Exporter<T>) exporterMap.remove(protocolKey);
        if (exporter != null) {
            exporter.destroy();
        }

        ProviderMessageRouter requestRouter = IP_PORT_TO_REQUEST_ROUTER.get(ipPort);
        if (requestRouter != null) {
            requestRouter.removeProvider(provider);
        }

        log.info("DefaultRpcExporter unexport Success: url={}", url);
    }

    @Override
    public boolean isAvailable() {
        return server.isAvailable();
    }

    @Override
    public void destroy() {
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
