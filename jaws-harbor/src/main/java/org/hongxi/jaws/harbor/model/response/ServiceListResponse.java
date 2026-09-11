package org.hongxi.jaws.harbor.model.response;

import org.hongxi.jaws.harbor.model.Response;

import java.util.List;

/**
 * Response to {@link org.hongxi.jaws.harbor.model.request.ServiceListRequest}.
 *
 * @author shenhongxi
 */
public class ServiceListResponse extends Response {

    private int count;
    private List<String> serviceNames;

    public ServiceListResponse() {
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public List<String> getServiceNames() {
        return serviceNames;
    }

    public void setServiceNames(List<String> serviceNames) {
        this.serviceNames = serviceNames;
    }
}
