package org.hongxi.jaws.wire;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Abstraction over "how does a target name become a set of backend addresses",
 * mirroring grpc-java's {@code io.grpc.NameResolver}. It is the raw-client
 * discovery seam: {@link ManagedChannel} drives its backend pool from a
 * {@code NameResolver}, so a DNS name (e.g. a Kubernetes headless Service)
 * resolves to the current set of endpoints and re-resolves to track changes.
 * <p>
 * This lives entirely on the raw {@link WireClient}/{@link ManagedChannel} path.
 * The Jaws RPC path performs discovery through the {@code Registry} layer
 * (Nacos / ZooKeeper / harbor) instead and does not use this interface.
 *
 * @author shenhongxi
 */
public interface NameResolver {

    /**
     * Callback through which a resolver delivers the current (and updated) set
     * of backend addresses to its consumer.
     */
    interface Listener {
        /**
         * @param addresses the full, current set of equivalent backend addresses;
         *                  the consumer reconciles its connection pool against this list
         */
        void onAddresses(List<InetSocketAddress> addresses);

        /**
         * @param error a resolution failure; the consumer keeps its last known set
         */
        void onError(Throwable error);
    }

    /**
     * Begin resolution. Implementations deliver an initial result promptly and,
     * where applicable, keep pushing updates until {@link #shutdown()}.
     *
     * @param listener callback for address updates
     */
    void start(Listener listener);

    /**
     * Request an out-of-band re-resolve. Default no-op for resolvers that push
     * updates on their own schedule (DNS) or never change (passthrough).
     */
    default void refresh() {
    }

    /**
     * Stop resolution and release resources. Idempotent.
     */
    void shutdown();

    /**
     * Construction context handed to a {@link NameResolverProvider} when it builds
     * a resolver from a target. Keeps the provider signature stable as new options
     * are added.
     *
     * @param dnsRefreshIntervalMs how often a {@code dns}-backed resolver
     *                             re-resolves, in milliseconds
     */
    record Args(long dnsRefreshIntervalMs) {
    }
}
