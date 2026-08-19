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
public class DefaultRpcReference<T> extends AbstractReference<T> {

    private static final Logger log = LoggerFactory.getLogger(DefaultRpcReference.class);

    protected Client client;

    public DefaultRpcReference(Class<T> interfaceClass, URL url) {
        super(interfaceClass, url);

        client = ExtensionLoader.getExtensionLoader(EndpointFactory.class)
                .getExtension(url.getParameter(URLParamType.endpointFactory))
                .createClient(url);
    }

    @Override
    protected Response doCall(Request request) {
        try {
            // Use server-side group to support cross-group invocation
            request.setAttachment(URLParamType.group.getName(), url.getGroup());
            return client.request(request);
        } catch (TransportException exception) {
            throw new JawsServiceException("DefaultRpcReference call Error: url=" + url.getUri(), exception);
        }
    }

    @Override
    protected void decrActiveCount(Request request, Response response) {
        if (!(response instanceof Future)) {
            activeReferenceCount.decrementAndGet();
            return;
        }

        Future future = (Future) response;

        future.addListener(new FutureListener() {
            @Override
            public void operationComplete(Future future) throws Exception {
                activeReferenceCount.decrementAndGet();
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
        client.close();
        log.info("DefaultRpcReference destroy client: url={}", url);
    }
}
