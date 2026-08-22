package org.hongxi.jaws.protocol.jaws;

import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.rpc.*;
import org.hongxi.jaws.transport.Client;
import org.hongxi.jaws.transport.TransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jaws protocol reference.
 */
public class JawsReference<T> extends AbstractReference<T> {

    private static final Logger log = LoggerFactory.getLogger(JawsReference.class);

    protected Client client;

    public JawsReference(Class<T> interfaceClass, URL url) {
        super(interfaceClass, url);
        client = ExtensionLoader.getExtensionLoader(TransportFactory.class)
                .getExtension(url.getParameter(UrlParam.Transport.TRANSPORT_FACTORY))
                .createClient(url);
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
    protected void decrActiveCount(Response response) {
        if (response instanceof Future future) {
            future.addListener(future1 -> activeReferenceCount.decrementAndGet());
        } else {
            activeReferenceCount.decrementAndGet();
        }
    }

    @Override
    public boolean isAvailable() {
        return client.isAvailable();
    }

    @Override
    public void destroy() {
        client.close();
        log.info("JawsReference destroy: url={}", url);
    }
}
