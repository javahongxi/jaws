package org.hongxi.jaws.wire;

/**
 * Load balance policies available for {@link ManagedChannel}.
 *
 * @author shenhongxi
 * @see ManagedChannel
 */
public enum LoadBalancePolicy {
    /**
     * Distribute calls evenly across all addresses in sequence.
     * Analogous to grpc-java's {@code RoundRobinLoadBalancer}.
     */
    ROUND_ROBIN,
    /**
     * Stick to the first available address; on failure, fail over to
     * the next address in order. Analogous to grpc-java's
     * {@code PickFirstLoadBalancer}.
     */
    PICK_FIRST
}
