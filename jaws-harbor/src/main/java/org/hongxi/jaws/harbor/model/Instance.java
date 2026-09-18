package org.hongxi.jaws.harbor.model;

import java.util.Map;

/**
 * A service instance registered with the naming service.
 * <p>
 * Field names match the nacos-client {@code com.alibaba.nacos.api.naming.pojo.Instance}
 * JSON wire format exactly, ensuring full compatibility.
 *
 * @author shenhongxi
 */
public class Instance {

    private String instanceId;
    private String ip;
    private int port;
    private double weight = 1.0;
    private boolean healthy = true;
    private boolean enabled = true;
    private boolean ephemeral = true;
    /** Nacos fills a blank cluster with this name client-side; we keep parity. */
    private String clusterName = "DEFAULT";
    private String serviceName;
    private Map<String, String> metadata;

    public Instance() {
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
