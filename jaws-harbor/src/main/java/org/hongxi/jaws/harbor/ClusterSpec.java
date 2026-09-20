package org.hongxi.jaws.harbor;

import java.util.ArrayList;
import java.util.List;

/**
 * Initial cluster peers a {@link HarborServer} joins at startup, parsed from the
 * URL parameter {@link HarborServer#PARAM_CLUSTER_MEMBERS} in the same
 * {@code host:port,host:port} shape as Nacos's {@code serverAddr} — and mirrored
 * on the client by {@code HarborClientConfig.ofCluster}.
 * <p>
 * Blank or {@code null} input is a valid single-node bootstrap (empty list);
 * each entry is trimmed, blank entries dropped.
 *
 * @author shenhongxi
 */
public record ClusterSpec(List<String> serverList) {

    public ClusterSpec {
        serverList = serverList == null ? List.of() : List.copyOf(serverList);
    }

    public static ClusterSpec fromServerAddr(String serverAddr) {
        if (serverAddr == null || serverAddr.isEmpty()) {
            return new ClusterSpec(List.of());
        }
        List<String> addresses = new ArrayList<>();
        for (String each : serverAddr.split(",")) {
            if (!each.isBlank()) {
                addresses.add(each.trim());
            }
        }
        return new ClusterSpec(addresses);
    }

    public boolean isEmpty() {
        return serverList.isEmpty();
    }
}
