package org.hongxi.jaws.transport;

import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Base implementation of {@link EndpointFactory} that manages shared server lifecycle.
 * <p>
 * All services on the same ip:port share a single server (channel),
 * similar to Dubbo's design. Compatibility of transport-level parameters is
 * validated by {@link JawsFrameworkUtils#checkIfCanShareServiceChannel(URL, URL)}.
 * <p>
 * Created by shenhongxi on 2020/7/31.
 */
public abstract class AbstractEndpointFactory implements EndpointFactory {
    private static final Logger log = LoggerFactory.getLogger(AbstractEndpointFactory.class);

    /**
     * Maintains the service list for shared channels.
     **/
    protected Map<String, Server> ipPort2Server = new HashMap<>();
    protected ConcurrentMap<Server, Set<String>> server2Urls = new ConcurrentHashMap<>();

    @Override
    public Server createServer(URL url, MessageHandler messageHandler) {
        synchronized (ipPort2Server) {
            String ipPort = url.getServerPortStr();
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

            Server server = ipPort2Server.get(ipPort);

            if (server != null) {
                if (!JawsFrameworkUtils.checkIfCanShareServiceChannel(server.getUrl(), url)) {
                    throw new JawsFrameworkException(
                            "Service export Error: share channel but some config param is different, " +
                                    "protocol or codec or serialize or maxContentLength or maxServerConnections " +
                                    "or maxWorkerThreads or heartbeatFactory, source="
                                    + server.getUrl() + " target=" + url, JawsErrorMsgConstants.FRAMEWORK_EXPORT_ERROR);
                }

                saveEndpoint2Urls(server2Urls, server, protocolKey);

                return server;
            }

            log.info("{} create shared server: url={}", this.getClass().getSimpleName(), url);

            url = url.createCopy();
            // Shared server port: clear path since multiple interfaces exist
            url.setPath("");

            server = innerCreateServer(url, messageHandler);

            ipPort2Server.put(ipPort, server);
            saveEndpoint2Urls(server2Urls, server, protocolKey);

            return server;
        }
    }

    @Override
    public Client createClient(URL url) {
        log.info(this.getClass().getSimpleName() + " create client: url={}", url);
        return innerCreateClient(url);
    }

    @Override
    public void safeReleaseResource(Server server, URL url) {
        synchronized (ipPort2Server) {
            String ipPort = url.getServerPortStr();
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

            if (server != ipPort2Server.get(ipPort)) {
                destroy(server);
                return;
            }

            Set<String> urls = server2Urls.get(server);
            urls.remove(protocolKey);

            if (urls.isEmpty()) {
                destroy(server);
                ipPort2Server.remove(ipPort);
                server2Urls.remove(server);
            }
        }
    }

    @Override
    public void safeReleaseResource(Client client, URL url) {
        destroy(client);
    }

    private <T extends Endpoint> void destroy(T endpoint) {
        endpoint.close();
    }

    protected abstract Server innerCreateServer(URL url, MessageHandler messageHandler);

    protected abstract Client innerCreateClient(URL url);

    private <T> void saveEndpoint2Urls(ConcurrentMap<T, Set<String>> map, T endpoint, String namespace) {
        Set<String> sets = map.get(endpoint);

        if (sets == null) {
            sets = new HashSet<>();
            sets.add(namespace);
            // 规避并发问题，因为有release逻辑存在，所以这里的sets预先add了namespace
            map.putIfAbsent(endpoint, sets);
            sets = map.get(endpoint);
        }

        sets.add(namespace);
    }
}
