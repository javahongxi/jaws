package org.hongxi.jaws.wire;

/**
 * SPI descriptor for a {@link LoadBalancer}: how to name it, how to prioritise it
 * when several providers claim the same name, and how to instantiate it. Mirrors
 * grpc-java's {@code io.grpc.LoadBalancerProvider}.
 * <p>
 * Discovered via {@link java.util.ServiceLoader} (register an implementation in
 * {@code META-INF/services/org.hongxi.jaws.wire.LoadBalancerProvider}) or
 * registered at runtime through {@link LoadBalancerRegistry#register}.
 *
 * @author shenhongxi
 */
public interface LoadBalancerProvider {

    /**
     * @return the policy name used to select this load balancer (e.g.
     *         {@code "round_robin"}); stable and case-sensitive
     */
    String getName();

    /**
     * @return the balance of this implementation's class name / simple id, used
     *         only for diagnostics
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Higher priority wins when multiple providers share a {@link #getName() name}.
     * Built-ins use {@code 5}.
     */
    default int getPriority() {
        return 5;
    }

    /**
     * @return a fresh, empty load balancer instance (no backend pool yet)
     */
    LoadBalancer newLoadBalancer();
}
