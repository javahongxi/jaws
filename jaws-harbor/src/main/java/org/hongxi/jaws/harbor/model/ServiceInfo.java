package org.hongxi.jaws.harbor.model;

import java.util.List;

/**
 * Service information returned by the naming service.
 * <p>
 * Contains the list of healthy {@link Instance} hosts for a service,
 * along with caching and checksum metadata. Field names match the
 * nacos-client JSON wire format.
 *
 * @author shenhongxi
 */
public class ServiceInfo {

    private String name;
    private String groupName;
    private String clusters = "";
    private long cacheMillis = 10000;
    private long lastRefTime;
    private String checksum = "";
    private boolean allIPs;
    private boolean reachProtectionThreshold;
    private List<Instance> hosts;

    public ServiceInfo() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getClusters() {
        return clusters;
    }

    public void setClusters(String clusters) {
        this.clusters = clusters;
    }

    public long getCacheMillis() {
        return cacheMillis;
    }

    public void setCacheMillis(long cacheMillis) {
        this.cacheMillis = cacheMillis;
    }

    public long getLastRefTime() {
        return lastRefTime;
    }

    public void setLastRefTime(long lastRefTime) {
        this.lastRefTime = lastRefTime;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public boolean isAllIPs() {
        return allIPs;
    }

    public void setAllIPs(boolean allIPs) {
        this.allIPs = allIPs;
    }

    public boolean isReachProtectionThreshold() {
        return reachProtectionThreshold;
    }

    public void setReachProtectionThreshold(boolean reachProtectionThreshold) {
        this.reachProtectionThreshold = reachProtectionThreshold;
    }

    public List<Instance> getHosts() {
        return hosts;
    }

    public void setHosts(List<Instance> hosts) {
        this.hosts = hosts;
    }
}
