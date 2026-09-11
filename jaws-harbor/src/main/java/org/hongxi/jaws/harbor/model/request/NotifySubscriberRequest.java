package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.ServiceInfo;

/**
 * Server→client push: notifies a subscriber that service instances have changed.
 *
 * @author shenhongxi
 */
public class NotifySubscriberRequest extends Request {

    private String serviceName;
    private String groupName;
    private ServiceInfo serviceInfo;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public ServiceInfo getServiceInfo() {
        return serviceInfo;
    }

    public void setServiceInfo(ServiceInfo serviceInfo) {
        this.serviceInfo = serviceInfo;
    }
}
