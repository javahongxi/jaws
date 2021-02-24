package org.hongxi.jaws.transport.support;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.exception.JawsErrorMsgConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.*;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * abstract endpoint factory
 *
 * <pre>
 * 		一些约定：
 *
 * 		1） service :
 * 			1.1） not share channel :  某个service暴露服务的时候，不期望和别的service共享服务，明哲自保，比如你说：我很重要，我很重要。
 *
 * 			1.2） share channel ： 某个service 暴露服务的时候，如果有某个模块，但是拆成10个接口，可以使用这种方式，不过有一些约束条件：接口的几个serviceConfig配置需要保持一致。
 *
 * 				不允许差异化的配置如下：
 * 					protocol, codec , serialization, maxContentLength , maxServerConnection , maxWorkerThread, workerQueueSize, heartbeatFactory
 *
 * 		2）心跳机制：
 *
 * 			不同的protocol的心跳包格式可能不一样，无法进行强制，那么通过可扩展的方式，依赖heartbeatFactory进行heartbeat包的创建，
 * 			同时对于service的messageHandler进行wrap heartbeat包的处理。
 *
 * 			对于service来说，把心跳包当成普通的request处理，因为这种heartbeat才能够探测到整个service处理的关键路径的可用状况
 *
 * </pre>
 *
 *
 * Created by shenhongxi on 2020/7/31.
 */
public abstract class AbstractEndpointFactory implements EndpointFactory {
    private static final Logger logger = LoggerFactory.getLogger(AbstractEndpointFactory.class);

    /** 维持share channel 的service列表 **/
    protected Map<String, Server> ipPort2ServerShareChannel = new HashMap<>();
    protected ConcurrentMap<Server, Set<String>> server2UrlsShareChannel = new ConcurrentHashMap<>();

    @Override
    public Server createServer(URL url, MessageHandler messageHandler) {
        synchronized (ipPort2ServerShareChannel) {
            String ipPort = url.getServerPortStr();
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

            boolean shareChannel = url.getBooleanParameter(URLParamType.shareChannel.getName(),
                    URLParamType.shareChannel.boolValue());

            if (!shareChannel) { // 独享一个端口
                logger.info(this.getClass().getSimpleName() + " create no_share_channel server: url={}", url);

                // 如果端口已经被使用了，使用该server bind 会有异常
                return innerCreateServer(url, messageHandler);
            }

            logger.info(this.getClass().getSimpleName() + " create share_channel server: url={}", url);

            Server server = ipPort2ServerShareChannel.get(ipPort);

            if (server != null) {
                // can't share service channel
                if (!JawsFrameworkUtils.checkIfCanShareServiceChannel(server.getUrl(), url)) {
                    throw new JawsFrameworkException(
                            "Service export Error: share channel but some config param is different, " +
                                    "protocol or codec or serialize or maxContentLength or maxServerConnection " +
                                    "or maxWorkerThread or heartbeatFactory, source="
                                    + server.getUrl() + " target=" + url, JawsErrorMsgConstants.FRAMEWORK_EXPORT_ERROR);
                }

                saveEndpoint2Urls(server2UrlsShareChannel, server, protocolKey);

                return server;
            }

            url = url.createCopy();
            url.setPath(""); // 共享server端口，由于有多个interfaces存在，所以把path设置为空

            server = innerCreateServer(url, messageHandler);

            ipPort2ServerShareChannel.put(ipPort, server);
            saveEndpoint2Urls(server2UrlsShareChannel, server, protocolKey);

            return server;
        }
    }

    @Override
    public Client createClient(URL url) {
        logger.info(this.getClass().getSimpleName() + " create client: url={}", url);
        return innerCreateClient(url);
    }

    @Override
    public void safeReleaseResource(Server server, URL url) {
        boolean shareChannel = url.getBooleanParameter(URLParamType.shareChannel.getName(),
                URLParamType.shareChannel.boolValue());

        if (!shareChannel) {
            destroy(server);
            return;
        }

        synchronized (ipPort2ServerShareChannel) {
            String ipPort = url.getServerPortStr();
            String protocolKey = JawsFrameworkUtils.getProtocolKey(url);

            if (server != ipPort2ServerShareChannel.get(ipPort)) {
                destroy(server);
                return;
            }

            Set<String> urls = server2UrlsShareChannel.get(server);
            urls.remove(protocolKey);

            if (urls.isEmpty()) {
                destroy(server);
                ipPort2ServerShareChannel.remove(ipPort);
                server2UrlsShareChannel.remove(server);
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
