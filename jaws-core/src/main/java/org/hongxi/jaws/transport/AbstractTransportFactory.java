package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Base implementation of {@link TransportFactory} that manages shared server lifecycle.
 * <p>
 * All services on the same host:port share a single server (channel),
 * similar to Dubbo's design.
 * <p>
 * Created by shenhongxi on 2020/7/31.
 */
public abstract class AbstractTransportFactory implements TransportFactory {
    private static final Logger log = LoggerFactory.getLogger(AbstractTransportFactory.class);

    /**
     * Shared server pool keyed by host:port.
     */
    protected final Map<String, Server> serverMap = new HashMap<>();

    @Override
    public Server createServer(URL url, MessageHandler messageHandler) {
        synchronized (serverMap) {
            String hostPort = url.getHostPort();
            Server server = serverMap.get(hostPort);
            if (server != null) {
                return server;
            }

            log.info("{} create shared server: url={}", this.getClass().getSimpleName(), url);

            url = url.createCopy();
            url.setPath("");
            server = innerCreateServer(url, messageHandler);
            serverMap.put(hostPort, server);
            return server;
        }
    }

    @Override
    public Client createClient(URL url) {
        log.info("{} create client: url={}", this.getClass().getSimpleName(), url);
        return innerCreateClient(url);
    }

    protected abstract Server innerCreateServer(URL url, MessageHandler messageHandler);

    protected abstract Client innerCreateClient(URL url);
}