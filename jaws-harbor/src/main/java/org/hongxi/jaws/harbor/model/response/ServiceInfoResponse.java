package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;
import org.hongxi.jaws.harbor.model.ServiceInfo;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.SubscribeServiceRequest}
 * and {@link org.hongxi.jaws.harbor.model.request.ServiceQueryRequest}.
 * Shared because both carry identical {@link ServiceInfo} payload.
 *
 * @author shenhongxi
 */
public class ServiceInfoResponse extends Response {

    private ServiceInfo serviceInfo;

    public ServiceInfoResponse() {
    }

    public ServiceInfo getServiceInfo() {
        return serviceInfo;
    }

    public void setServiceInfo(ServiceInfo serviceInfo) {
        this.serviceInfo = serviceInfo;
    }
}
