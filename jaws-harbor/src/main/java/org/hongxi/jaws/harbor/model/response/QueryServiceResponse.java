package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;
import org.hongxi.jaws.harbor.model.ServiceInfo;

/**
 * Reply to a one-off service query, used by clients that do not subscribe.
 *
 * @author shenhongxi
 */
public class QueryServiceResponse extends Response {

    private ServiceInfo serviceInfo;

    public QueryServiceResponse() {
    }

    public ServiceInfo getServiceInfo() {
        return serviceInfo;
    }

    public void setServiceInfo(ServiceInfo serviceInfo) {
        this.serviceInfo = serviceInfo;
    }
}
