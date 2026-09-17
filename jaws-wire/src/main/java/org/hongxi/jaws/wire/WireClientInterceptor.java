package org.hongxi.jaws.wire;

/**
 * Intercepts client-side gRPC calls before they are sent to the server.
 * <p>
 * Client interceptors form a chain; each interceptor can inspect/modify the
 * request, inject metadata, or short-circuit the call. The model mirrors
 * grpc-java's {@code ClientInterceptor}:
 * <ol>
 *   <li>The interceptor receives a {@link WireClientCall} facade and a
 *       {@link WireClientCallHandler} representing the next element.</li>
 *   <li>It returns a {@link WireClientCall} that the framework uses to send
 *       the request. Typically this wraps the real call via
 *       {@link ForwardingClientCall}.</li>
 * </ol>
 * <p>
 * Common use cases:
 * <ul>
 *   <li>Inject authentication tokens into request metadata</li>
 *   <li>Add tracing / logging metadata</li>
 *   <li>Call timing and metrics</li>
 * </ul>
 * <p>
 * Thread-safe: implementations must be safe for concurrent invocation.
 *
 * @author shenhongxi
 * @see WireClient#addInterceptor(WireClientInterceptor)
 */
public interface WireClientInterceptor {

    /**
     * Intercept a client-side gRPC call. The interceptor can:
     * <ul>
     *   <li>Call {@code next.newCall(call)} to continue the chain, wrapping
     *       the returned {@link WireClientCall} to observe/modify outbound
     *       requests or inbound responses</li>
     *   <li>Return a custom {@link WireClientCall} to short-circuit the call
     *       (e.g. mock responses, cached results)</li>
     * </ul>
     *
     * @param call the client call facade for the current call
     * @param next the next handler in the interceptor chain
     * @return a client call that the framework uses to send the request
     */
    WireClientCall interceptCall(WireClientCall call, WireClientCallHandler next);
}
