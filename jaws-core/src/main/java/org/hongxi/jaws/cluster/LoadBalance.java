package org.hongxi.jaws.cluster;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;

/**
 * Load balance SPI for selecting a provider reference per request.
 * <p>
 * An instance is bound to one consumer service: the registry notify thread
 * pushes the latest address list through {@link #onRefresh}, while business
 * threads call {@link #select} (or {@link #selectCandidates} for retries)
 * on every invocation. Implementations must therefore be thread-safe and
 * treat the refreshed list as a whole replacement rather than mutating it.
 * <p>
 * Created by shenhongxi on 2021/4/23.
 *
 * @see org.hongxi.jaws.cluster.loadbalance.AbstractLoadBalance
 */
@Spi
public interface LoadBalance<T> {

    /**
     * Refresh the candidate reference list after an address-list change.
     * Called on the registry notify thread; the given list must be taken
     * over as a whole and never modified in place afterwards.
     *
     * @param references the latest available references for this service
     */
    void onRefresh(List<Reference<T>> references);

    /**
     * Select a single available reference to serve the request.
     *
     * @param request the invocation to be routed
     * @return the selected reference
     * @throws org.hongxi.jaws.exception.JawsServiceException if no available reference exists
     */
    Reference<T> select(Request request);

    /**
     * Select available candidate references in priority order for
     * retry-style invocation (e.g. failover tries them one by one).
     *
     * @param request the invocation to be routed
     * @return non-empty candidate list, most preferred first
     * @throws org.hongxi.jaws.exception.JawsServiceException if no available reference exists
     */
    List<Reference<T>> selectCandidates(Request request);
}