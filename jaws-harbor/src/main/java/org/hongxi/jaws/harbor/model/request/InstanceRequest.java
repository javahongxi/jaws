package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.Request;

/**
 * Register or deregister a service instance.
 * The {@code type} field is {@code "registerInstance"} or {@code "deregisterInstance"}.
 *
 * @author shenhongxi
 */
public class InstanceRequest extends Request {

    private String serviceName;
    private String groupName;
    private String type;
    private Instance instance;

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Instance getInstance() {
        return instance;
    }

    public void setInstance(Instance instance) {
        this.instance = instance;
    }
}
