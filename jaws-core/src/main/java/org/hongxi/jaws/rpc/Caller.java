package org.hongxi.jaws.rpc;

import java.util.concurrent.Flow;

/**
 * Common invocation abstraction shared by both sides of an RPC call: a
 * {@link Provider} on the server side and a {@link Reference} on the client side both
 * accept a {@link Request} through {@link #call(Request)} and return a
 * {@link Response}. This unification lets clusters and filters handle callers
 * uniformly, mirroring Dubbo's single {@code Invoker} concept.
 *
 * <p>Created by shenhongxi on 2021/3/6.
 *
 * @see Provider
 * @see Reference
 */
public interface Caller<T> extends Endpoint {

    Class<T> getInterface();

    Response call(Request request);

    /**
     * Open a server-streaming call and return a {@link Flow.Publisher} that emits
     * response items. Only supported by callers that back streaming transports.
     *
     * @param request the RPC request
     * @return a publisher emitting streamed response items
     */
    default Flow.Publisher<Object> callStream(Request request) {
        throw new UnsupportedOperationException("Streaming not supported by this caller");
    }
}