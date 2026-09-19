package org.hongxi.jaws.wire;

import java.util.List;

/**
 * A routing decision snapshot produced by a {@link LoadBalancer}. Mirrors
 * grpc-java's {@code SubchannelPicker}, adapted to this channel's "return the
 * attempt order, the caller fails over down it" model.
 *
 * @author shenhongxi
 * @see LoadBalancer#picker()
 */
public interface SubchannelPicker {

    /**
     * Decide which backends a single call should use, in attempt order: element 0
     * is preferred, the rest are the failover sequence. The channel skips entries
     * that are not {@link WireClient#isAvailable() available} and tries the rest.
     *
     * @return an ordered, possibly empty list (empty means no backend to try now)
     */
    List<WireClient> pick();
}
