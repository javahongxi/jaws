package org.hongxi.jaws.cluster.router;

import org.hongxi.jaws.cluster.Router;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RouterChain applies a sequence of {@link Router} instances to filter
 * candidate references before load balance selection.
 *
 * @param <T> service type
 */
public class RouterChain<T> {

    private static final Logger log = LoggerFactory.getLogger(RouterChain.class);

    private volatile List<Router<T>> routers = Collections.emptyList();

    /**
     * Add a router to the chain.
     */
    public void addRouter(Router<T> router) {
        List<Router<T>> newRouters = new ArrayList<>(routers);
        newRouters.add(router);
        this.routers = Collections.unmodifiableList(newRouters);
    }

    /**
     * Apply all routers in sequence to filter the references.
     *
     * @param references the original reference list
     * @param request    the current RPC request
     * @return filtered reference list
     */
    public List<Reference<T>> route(List<Reference<T>> references, Request request) {
        List<Reference<T>> current = references;
        for (Router<T> router : routers) {
            try {
                List<Reference<T>> filtered = router.route(current, request);
                if (filtered == null || filtered.isEmpty()) {
                    log.warn("Router {} filtered all references for request {}, falling back to previous list",
                            router.getClass().getSimpleName(), request.getMethodName());
                    // fall back to the list before this router
                    return current;
                }
                current = filtered;
            } catch (Exception e) {
                log.warn("Router {} threw exception, skipping: {}", router.getClass().getSimpleName(), e.getMessage());
            }
        }
        return current;
    }

    public boolean hasRouters() {
        return !routers.isEmpty();
    }
}
