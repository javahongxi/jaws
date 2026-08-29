package org.hongxi.jaws.transport;

import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Base implementation of {@link TransportFactory} that manages shared server and client lifecycle.
 * <p>
 * All services on the same host:port share a single server (channel),
 * similar to Dubbo's design. Symmetrically, all services targeting the same
 * remote host:port share a single client connection, multiplexing requests
 * over it; the connection is closed when the last reference is released.
 * <p>
 * Created by shenhongxi on 2020/7/31.
 */
public abstract class AbstractTransportFactory implements TransportFactory {
    private static final Logger log = LoggerFactory.getLogger(AbstractTransportFactory.class);

    /**
     * Shared server pool keyed by host:port, with reference counts.
     */
    protected final Map<String, Server> serverMap = new HashMap<>();
    protected final Map<String, Integer> serverRefCounts = new HashMap<>();

    /**
     * Shared client pool keyed by remote host:port, with reference counts.
     */
    protected final Map<String, Client> clientMap = new HashMap<>();
    protected final Map<String, Integer> clientRefCounts = new HashMap<>();

    @Override
    public Server createServer(URL url, MessageHandler messageHandler) {
        synchronized (serverMap) {
            String hostPort = url.getHostPort();
            Server server = serverMap.get(hostPort);
            if (server != null) {
                int refCount = serverRefCounts.merge(hostPort, 1, Integer::sum);
                log.info("{} reuse shared server: url={}, refCount={}",
                        this.getClass().getSimpleName(), url, refCount);
                return server;
            }

            log.info("{} create shared server: url={}", this.getClass().getSimpleName(), url);

            url = url.createCopy();
            url.setPath("");
            server = innerCreateServer(url, messageHandler);
            serverMap.put(hostPort, server);
            serverRefCounts.put(hostPort, 1);
            return server;
        }
    }

    @Override
    public void releaseServer(Server server) {
        if (server == null) {
            return;
        }
        String hostPort = server.getUrl().getHostPort();
        synchronized (serverMap) {
            if (server != serverMap.get(hostPort)) {
                // Not (or no longer) managed by the shared pool; close it directly
                log.warn("{} release unmanaged server: url={}", this.getClass().getSimpleName(), hostPort);
                server.close();
                return;
            }

            int refCount = serverRefCounts.merge(hostPort, -1, Integer::sum);
            if (refCount <= 0) {
                serverMap.remove(hostPort);
                serverRefCounts.remove(hostPort);
                server.close();
                log.info("{} closed shared server: url={}", this.getClass().getSimpleName(), hostPort);
            }
        }
    }

    @Override
    public Client createClient(URL url) {
        synchronized (clientMap) {
            String hostPort = url.getHostPort();
            Client client = clientMap.get(hostPort);
            if (client != null) {
                int refCount = clientRefCounts.merge(hostPort, 1, Integer::sum);
                log.info("{} reuse shared client: url={}, refCount={}",
                        this.getClass().getSimpleName(), url, refCount);
                return client;
            }

            log.info("{} create shared client: url={}", this.getClass().getSimpleName(), url);

            url = url.createCopy();
            url.setPath("");
            client = innerCreateClient(url);
            clientMap.put(hostPort, client);
            clientRefCounts.put(hostPort, 1);
            return client;
        }
    }

    @Override
    public void releaseClient(Client client) {
        if (client == null) {
            return;
        }
        String hostPort = client.getUrl().getHostPort();
        synchronized (clientMap) {
            if (client != clientMap.get(hostPort)) {
                // Not (or no longer) managed by the shared pool; close it directly
                log.warn("{} release unmanaged client: url={}", this.getClass().getSimpleName(), hostPort);
                client.close();
                return;
            }

            int refCount = clientRefCounts.merge(hostPort, -1, Integer::sum);
            if (refCount <= 0) {
                clientMap.remove(hostPort);
                clientRefCounts.remove(hostPort);
                client.close();
                log.info("{} closed shared client: url={}", this.getClass().getSimpleName(), hostPort);
            }
        }
    }

    protected abstract Server innerCreateServer(URL url, MessageHandler messageHandler);

    protected abstract Client innerCreateClient(URL url);
}