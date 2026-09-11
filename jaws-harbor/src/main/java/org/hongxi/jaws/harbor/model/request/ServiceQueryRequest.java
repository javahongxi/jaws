package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Request;

/**
 * Query service info including all registered instances.
 *
 * @author shenhongxi
 */
public class ServiceQueryRequest extends Request {

    private String serviceName;
    private String groupName;

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
}
