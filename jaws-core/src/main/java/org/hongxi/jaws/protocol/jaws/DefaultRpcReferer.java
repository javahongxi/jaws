package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.EndpointFactory;
import org.hongxi.jaws.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shenhongxi on 2021/4/21.
 */
public class DefaultRpcReferer<T> extends AbstractReferer<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultRpcReferer.class);

    protected Client client;
    protected EndpointFactory endpointFactory;

    public DefaultRpcReferer(Class<T> clz, URL url, URL serviceUrl) {
        super(clz, url, serviceUrl);

        endpointFactory =
                ExtensionLoader.getExtensionLoader(EndpointFactory.class).getExtension(
                        url.getParameter(URLParamType.endpointFactory.getName(), URLParamType.endpointFactory.value()));

        client = endpointFactory.createClient(url);
    }

    @Override
    protected Response doCall(Request request) {
        try {
            // 为了能够实现跨group请求，需要使用server端的group。
            request.setAttachment(URLParamType.group.getName(), serviceUrl.getGroup());
            return client.request(request);
        } catch (TransportException exception) {
            throw new JawsServiceException("DefaultRpcReferer call Error: url=" + url.getUri(), exception);
        }
    }

    @Override
    protected void decrActiveCount(Request request, Response response) {
        if (!(response instanceof Future)) {
            activeRefererCount.decrementAndGet();
            return;
        }

        Future future = (Future) response;

        future.addListener(new FutureListener() {
            @Override
            public void operationComplete(Future future) throws Exception {
                activeRefererCount.decrementAndGet();
            }
        });
    }

    @Override
    protected boolean doInit() {
        boolean result = client.open();

        return result;
    }

    @Override
    public boolean isAvailable() {
        return client.isAvailable();
    }

    @Override
    public void destroy() {
        endpointFactory.safeReleaseResource(client, url);
        log.info("DefaultRpcReferer destory client: url={}", url);
    }
}
