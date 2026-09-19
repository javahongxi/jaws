package org.hongxi.jaws.wire;

import java.util.List;

/**
 * The load-balancing seam of a {@link ManagedChannel}, analogous to grpc-java's
 * {@code io.grpc.LoadBalancer}: given the live set of backends, it decides the
 * order in which a call should try them.
 * <p>
 * A load balancer is a per-channel, stateful object. {@link ManagedChannel}
 * calls {@link #resolvedAddresses(List)} whenever its backend pool changes and
 * {@link #picker()} to obtain the routing decision for each call. Implementations
 * must be thread-safe: {@code resolvedAddresses} runs on the address-sync thread
 * while {@code picker} runs on caller threads.
 *
 * @author shenhongxi
 * @see LoadBalancerProvider
 * @see LoadBalancerRegistry
 */
public interface LoadBalancer {

    /**
     * Replace the current backend pool. The list is a defensive snapshot owned by
     * the load balancer; the channel will not mutate it.
     *
     * @param backends the live backends, in stable resolution order
     */
    void resolvedAddresses(List<WireClient> backends);

    /**
     * @return a picker capturing the current routing state; may be reused across
     *         calls until the next {@link #resolvedAddresses(List)}
     */
    SubchannelPicker picker();

    /** Release any resources; invoked when the channel shuts down. */
    default void shutdown() {
    }
}
