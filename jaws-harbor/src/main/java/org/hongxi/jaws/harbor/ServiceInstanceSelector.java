package org.hongxi.jaws.harbor;

import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Narrows a service's instance list to what one caller asked for.
 * <p>
 * Always a copy: the cached {@link ServiceInfo} is shared by every reader and by
 * the push engine, so filtering it in place would hide instances from the
 * subscribers that did not ask for the narrower view. Predicates follow Nacos
 * {@code ServiceUtil.doSelectInstances} — a cluster allow-list (empty means all),
 * the enabled flag only where the caller opts in, and health on request.
 * <p>
 * The two opt-ins are not symmetric, matching Nacos: a query or a subscribe
 * reply drops disabled instances, while a push keeps them so a subscriber still
 * learns that an instance it holds went out of service.
 *
 * @author shenhongxi
 */
final class ServiceInstanceSelector {

    private ServiceInstanceSelector() {
    }

    /**
     * @param clustersCsv  comma-separated cluster allow-list, empty for all
     * @param healthyOnly  keep only healthy instances
     * @param enableOnly   keep only enabled instances
     */
    static ServiceInfo select(ServiceInfo source, String clustersCsv,
                              boolean healthyOnly, boolean enableOnly) {
        String clusters = clustersCsv == null ? "" : clustersCsv;
        Set<String> allowed = clusters.isEmpty()
                ? Set.of()
                : new HashSet<>(Arrays.asList(clusters.split(",")));

        ServiceInfo result = new ServiceInfo();
        result.setName(source.getName());
        result.setGroupName(source.getGroupName());
        result.setCacheMillis(source.getCacheMillis());
        result.setChecksum(source.getChecksum());
        result.setClusters(clusters);
        result.setLastRefTime(System.currentTimeMillis());
        result.setReachProtectionThreshold(false);

        List<Instance> hosts = new ArrayList<>();
        List<Instance> all = source.getHosts() == null ? List.of() : source.getHosts();
        for (Instance each : all) {
            if (!allowed.isEmpty() && !allowed.contains(each.getClusterName())) {
                continue;
            }
            if (enableOnly && !each.isEnabled()) {
                continue;
            }
            if (healthyOnly && !each.isHealthy()) {
                continue;
            }
            hosts.add(each);
        }
        result.setHosts(hosts);
        return result;
    }
}
