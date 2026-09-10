package org.hongxi.jaws.harbor.cluster;

import java.util.Objects;

/**
 * Represents a member node in the Harbor cluster.
 * <p>
 * Each member is identified by its address in the form {@code host:port},
 * which corresponds to the gRPC port that the member's {@code HarborServer}
 * is listening on.
 *
 * @author shenhongxi
 */
public record ClusterMember(String address) {

    public ClusterMember {
        Objects.requireNonNull(address, "address must not be null");
    }

    public String host() {
        int idx = address.lastIndexOf(':');
        return idx > 0 ? address.substring(0, idx) : address;
    }

    public int port() {
        int idx = address.lastIndexOf(':');
        return idx > 0 ? Integer.parseInt(address.substring(idx + 1)) : 0;
    }

    @Override
    public String toString() {
        return address;
    }
}
