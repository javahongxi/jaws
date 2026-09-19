package org.hongxi.jaws.wire;

import java.util.Collections;
import java.util.List;

/**
 * Sticks to the first usable backend and fails over in resolution order. The pick
 * result is simply the pool as resolved, so the channel's availability skip +
 * failover loop land on the first ready backend and only move on when it is not
 * usable or errors. Analogous to grpc-java's {@code PickFirstLoadBalancer}.
 *
 * @author shenhongxi
 */
public final class PickFirstLoadBalancer implements LoadBalancer {

    private volatile List<WireClient> backends = Collections.emptyList();

    @Override
    public void resolvedAddresses(List<WireClient> newBackends) {
        this.backends = List.copyOf(newBackends);
    }

    @Override
    public SubchannelPicker picker() {
        final List<WireClient> snapshot = this.backends;
        return () -> snapshot;
    }

    /** Provider registering {@code pick_first}. */
    public static final class Provider implements LoadBalancerProvider {
        @Override
        public String getName() {
            return "pick_first";
        }

        @Override
        public LoadBalancer newLoadBalancer() {
            return new PickFirstLoadBalancer();
        }
    }
}
