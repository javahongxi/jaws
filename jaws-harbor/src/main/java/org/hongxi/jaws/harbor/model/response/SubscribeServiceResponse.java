package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;
import org.hongxi.jaws.harbor.model.ServiceInfo;

/**
 * Reply to a subscribe: the subscriber is now registered, and this is the
 * current view of the service it will also be pushed on change.
 *
 * @author shenhongxi
 */
public class SubscribeServiceResponse extends Response {

    private ServiceInfo serviceInfo;

    public SubscribeServiceResponse() {
    }

    public ServiceInfo getServiceInfo() {
        return serviceInfo;
    }

    public void setServiceInfo(ServiceInfo serviceInfo) {
        this.serviceInfo = serviceInfo;
    }
}
