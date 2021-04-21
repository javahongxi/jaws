package org.hongxi.jaws.registry.support;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.registry.NotifyListener;
import org.hongxi.jaws.rpc.URL;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by shenhongxi on 2021/4/22.
 */
public class DirectRegistry extends AbstractRegistry {

    private ConcurrentHashMap<URL, Object> subscribeUrls = new ConcurrentHashMap();
    private List<URL> directUrls = new ArrayList<URL>();

    public DirectRegistry(URL url) {
        super(url);
        String address = url.getParameter("address");
        if (address.contains(",")) {
            try {
                String[] directUrlArray = address.split(",");
                for (String directUrl : directUrlArray) {
                    parseDirectUrl(directUrl);
                }
            } catch (Exception e) {
                throw new JawsFrameworkException(
                        String.format("parse direct url error, invalid direct registry address %s, address should be ip1:port1,ip2:port2 ..."));
            }
        } else {
            registerDirectUrl(url.getHost(), url.getPort());
        }
    }

    private void parseDirectUrl(String directUrl) {
        String[] ipAndPort = directUrl.split(":");
        String ip = ipAndPort[0];
        Integer port = Integer.parseInt(ipAndPort[1]);
        if (port < 0 || port > 65535) {
            throw new RuntimeException();
        }
        registerDirectUrl(ip, port);
    }

    private void registerDirectUrl(String ip, Integer port) {
        URL url = new URL(JawsConstants.REGISTRY_PROTOCOL_DIRECT,ip,port,"");
        directUrls.add(url);
    }

    private void parseIpAndPort(String directUrl) {
    }

    @Override
    protected void doRegister(URL url) {
        // do nothing
    }

    @Override
    protected void doUnregister(URL url) {
        // do nothing
    }

    @Override
    protected void doSubscribe(URL url, NotifyListener listener) {
        subscribeUrls.putIfAbsent(url, 1);
        listener.notify(this.getUrl(), doDiscover(url));
    }

    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        subscribeUrls.remove(url);
        listener.notify(this.getUrl(), doDiscover(url));
    }

    @Override
    protected List<URL> doDiscover(URL subscribeUrl) {
        return createSubscribeUrl(subscribeUrl);
    }

    private List<URL> createSubscribeUrl(URL subscribeUrl) {
        URL url = this.getUrl();
        List result = new ArrayList(directUrls.size());
        for (URL directUrl : directUrls) {
            URL tmp = subscribeUrl.createCopy();
            tmp.setHost(directUrl.getHost());
            tmp.setPort(directUrl.getPort());
            result.add(tmp);
        }
        return result;
    }

    @Override
    protected void doAvailable(URL url) {
        // do nothing
    }

    @Override
    protected void doUnavailable(URL url) {
        // do nothing
    }
}