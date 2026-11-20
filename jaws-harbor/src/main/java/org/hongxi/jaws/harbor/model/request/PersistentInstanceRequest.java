package org.hongxi.jaws.harbor.model.request;

import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.Request;

/**
 * Register or deregister a PERSISTENT service instance ({@code ephemeral=false}).
 * <p>
 * Mirrors the Nacos {@code PersistentInstanceRequest}: persistence rides its own
 * request type rather than an {@code InstanceRequest} carrying a flag, because the
 * two live under different consistency protocols — this one is replicated through
 * the Raft CP group and survives the client connection that created it, while
 * {@code InstanceRequest} rides the connection-liveness (Distro AP) model.
 * <p>
 * The {@code type} field is {@code "registerInstance"} or {@code "deregisterInstance"},
 * the same verbs as the ephemeral path.
 *
 * @author shenhongxi
 */
public class PersistentInstanceRequest extends Request {

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