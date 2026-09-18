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
    /** Comma-separated cluster allow-list; empty means every cluster. */
    private String cluster;
    private boolean healthyOnly;
    /** Carried for wire compatibility; harbor pushes over the stream, not UDP. */
    private int udpPort;

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

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public boolean isHealthyOnly() {
        return healthyOnly;
    }

    public void setHealthyOnly(boolean healthyOnly) {
        this.healthyOnly = healthyOnly;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }
}
