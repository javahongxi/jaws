package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.AbstractReference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.TransportFactory;
import org.hongxi.jaws.transport.TransportResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jaws protocol reference.
 */
public class JawsReference<T> extends AbstractReference<T> {

    private static final Logger log = LoggerFactory.getLogger(JawsReference.class);

    protected Client client;

    private final TransportFactory transportFactory;

    /** Guards against releasing the shared client more than once. */
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    public JawsReference(Class<T> interfaceClass, URL url) {
        super(interfaceClass, url);
        transportFactory = TransportResolver.resolve(url);
        client = transportFactory.createClient(url);
    }

    @Override
    protected boolean doInit() {
        return client.open();
    }

    @Override
    protected Response doCall(Request request) {
        request.setAttachment(UrlParam.Identity.GROUP.getName(), url.getGroup());
        return client.request(request);
    }

    @Override
    public Flow.Publisher<Object> callStream(Request request) {
        if (!isAvailable()) {
            throw new JawsServiceException(this.getClass().getSimpleName() +
                    " callStream failed: endpoint is not available, url=" + url.getUri());
        }
        request.setAttachment(UrlParam.Identity.GROUP.getName(), url.getGroup());
        return client.requestStream(request);
    }

    @Override
    public boolean isAvailable() {
        return client.isAvailable();
    }

    @Override
    public void destroy() {
        // CAS ensures the shared client is released exactly once even when
        // destroy() is invoked concurrently from multiple threads
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        transportFactory.releaseClient(client);
        log.info("JawsReference destroy: url={}", url);
    }
}
