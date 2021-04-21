package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.EndpointFactory;
import org.hongxi.jaws.transport.ProviderMessageRouter;
import org.hongxi.jaws.transport.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class DefaultRpcExporter<T> extends AbstractExporter<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultRpcExporter.class);

    protected final ConcurrentHashMap<String, ProviderMessageRouter> ipPort2RequestRouter;
    protected final ConcurrentHashMap<String, Exporter<?>> exporterMap;
    protected Server server;
    protected EndpointFactory endpointFactory;

    public DefaultRpcExporter(Provider<T> provider, URL url, ConcurrentHashMap<String, ProviderMessageRouter> ipPort2RequestRouter,
                              ConcurrentHashMap<String, Exporter<?>> exporterMap) {
        super(provider, url);
        this.exporterMap = exporterMap;
        this.ipPort2RequestRouter = ipPort2RequestRouter;

        ProviderMessageRouter requestRouter = initRequestRouter(url);
        endpointFactory =
                ExtensionLoader.getExtensionLoader(EndpointFactory.class).getExtension(
                        url.getParameter(URLParamType.endpointFactory.getName(), URLParamType.endpointFactory.value()));
        server = endpointFactory.createServer(url, requestRouter);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void unexport() {
        String protocolKey = JawsFrameworkUtils.getProtocolKey(url);
        String ipPort = url.getServerPortStr();

        Exporter<T> exporter = (Exporter<T>) exporterMap.remove(protocolKey);
        if (exporter != null) {
            exporter.destroy();
        }

        ProviderMessageRouter requestRouter = ipPort2RequestRouter.get(ipPort);
        if (requestRouter != null) {
            requestRouter.removeProvider(provider);
        }

        log.info("DefaultRpcExporter unexport Success: url={}", url);
    }

    @Override
    protected boolean doInit() {
        boolean result = server.open();
        if (result && getUrl().getPort() == 0){ // use random port
            ProviderMessageRouter requestRouter = this.ipPort2RequestRouter.remove(getUrl().getServerPortStr());
            if (requestRouter == null){
                throw new JawsFrameworkException("can not find message router. url:" + getUrl().getIdentity());
            }
            updateRealServerPort(server.getLocalAddress().getPort());
            this.ipPort2RequestRouter.put(getUrl().getServerPortStr(), requestRouter);
        }
        return result;
    }

    @Override
    public boolean isAvailable() {
        return server.isAvailable();
    }

    @Override
    public void destroy() {
        endpointFactory.safeReleaseResource(server, url);
        log.info("DefaultRpcExporter destroy Success: url={}", url);
    }

    protected ProviderMessageRouter initRequestRouter(URL url) {
        String ipPort = url.getServerPortStr();
        ProviderMessageRouter requestRouter = ipPort2RequestRouter.get(ipPort);

        if (requestRouter == null) {
            ProviderMessageRouter router = new ProviderMessageRouter(url);
            ipPort2RequestRouter.putIfAbsent(ipPort, router);
            requestRouter = ipPort2RequestRouter.get(ipPort);
        }
        requestRouter.addProvider(provider);

        return requestRouter;
    }
}
