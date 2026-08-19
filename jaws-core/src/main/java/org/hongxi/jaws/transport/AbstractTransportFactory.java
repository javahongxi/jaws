package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Base implementation of {@link TransportFactory} that manages shared server lifecycle.
 * <p>
 * All services on the same ip:port share a single server (channel),
 * similar to Dubbo's design.
 * <p>
 * Created by shenhongxi on 2020/7/31.
 */
public abstract class AbstractTransportFactory implements TransportFactory {
    private static final Logger log = LoggerFactory.getLogger(AbstractTransportFactory.class);

    /**
     * Shared server pool keyed by ip:port. Once created, a server lives
     * for the application lifetime (same as Dubbo's design).
     */
    protected final Map<String, Server> ipPort2Server = new HashMap<>();

    @Override
    public Server createServer(URL url, MessageHandler messageHandler) {
        synchronized (ipPort2Server) {
            String ipPort = url.getServerPortStr();

            Server server = ipPort2Server.get(ipPort);
            if (server != null) {
                return server;
            }

            log.info("{} create shared server: url={}", this.getClass().getSimpleName(), url);

            url = url.createCopy();
            // Shared server port: clear path since multiple interfaces exist
            url.setPath("");

            server = innerCreateServer(url, messageHandler);
            ipPort2Server.put(ipPort, server);

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
