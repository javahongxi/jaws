package org.hongxi.jaws.rpc;

import org.hongxi.jaws.stream.StreamSource;

import java.util.concurrent.CompletableFuture;

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
     * Async invocation that returns a {@link CompletableFuture} completed when
     * the response is ready. Default implementation bridges the synchronous
     * {@link #call(Request)} result.
     *
     * @param request the RPC request
     * @return a future completed with the response
     */
    default CompletableFuture<Response> callAsync(Request request) {
        return CompletableFuture.completedFuture(call(request));
    }

    /**
     * Unified streaming call.  Handles all streaming modes:
     * <ul>
     *   <li>{@code requestStream == null} → server-streaming</li>
     *   <li>{@code requestStream != null} → client-streaming or
     *       bidirectional-streaming</li>
     * </ul>
     *
     * @param request       the RPC request
     * @param requestStream a source of client request items that the framework subscribes to, or
     *                      {@code null} for server-streaming
     * @return a source emitting streamed response items
     */
    default StreamSource<Object> callStream(Request request, StreamSource<Object> requestStream) {
        throw new UnsupportedOperationException("Streaming not supported by this caller");
    }
}