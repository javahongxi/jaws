package org.hongxi.jaws.cluster;

import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;

import java.util.List;

/**
 * Router filters the available references before load balance selects one.
 * <p>
 * Routers are evaluated on every RPC call, allowing dynamic traffic control
 * based on request context, network conditions, or external configuration.
 *
 * @param <T> service type
 */
public interface Router<T> {

    /**
     * Filter the candidate references according to routing rules.
     *
     * @param references candidate references from the cluster
     * @param request    the current RPC request
     * @return filtered references; must not return null, return the original list if no filtering applies
     */
    List<Reference<T>> route(List<Reference<T>> references, Request request);
}
