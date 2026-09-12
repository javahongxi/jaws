package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.Request;

import java.util.List;

/**
 * Batch register service instances.
 * <p>
 * Sent by nacos-client when multiple instances are registered in a single call.
 * All instances belong to the same service (namespace + groupName + serviceName).
 *
 * @author shenhongxi
 */
public class BatchInstanceRequest extends Request {

    private String serviceName;
    private String groupName;
    private String type;
    private List<Instance> instances;

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

    public List<Instance> getInstances() {
        return instances;
    }

    public void setInstances(List<Instance> instances) {
        this.instances = instances;
    }
}
