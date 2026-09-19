package org.hongxi.jaws.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributes calls evenly across the backend pool, advancing a starting index on
 * every pick so successive calls rotate. The pick result is the whole pool rotated
 * to start at that index, so the channel's failover loop walks a full cycle.
 * Analogous to grpc-java's {@code RoundRobinLoadBalancer}.
 *
 * @author shenhongxi
 */
public final class RoundRobinLoadBalancer implements LoadBalancer {

    private volatile List<WireClient> backends = Collections.emptyList();
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public void resolvedAddresses(List<WireClient> newBackends) {
        this.backends = List.copyOf(newBackends);
    }

    @Override
    public SubchannelPicker picker() {
        // Capture the current pool; the rotation counter is shared and advanced per
        // pick, so a stale picker still balances against the live count.
        final List<WireClient> snapshot = this.backends;
        return () -> rotate(snapshot, counter.getAndIncrement());
    }

    private static List<WireClient> rotate(List<WireClient> list, int start) {
        int n = list.size();
        if (n == 0) {
            return Collections.emptyList();
        }
        int idx = Math.abs(start % n);
        List<WireClient> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(list.get((idx + i) % n));
        }
        return out;
    }

    /** Provider registering {@code round_robin}. */
    public static final class Provider implements LoadBalancerProvider {
        @Override
        public String getName() {
            return "round_robin";
        }

        @Override
        public LoadBalancer newLoadBalancer() {
            return new RoundRobinLoadBalancer();
        }
    }
}
