package org.hongxi.jaws.wire;

import com.google.protobuf.Message;

/**
 * Intercepts server-side gRPC calls before they reach the business handler.
 * <p>
 * Interceptors form a chain; each interceptor can inspect/modify the request,
 * short-circuit with an error or response, or delegate to the next interceptor.
 * Common use cases:
 * <ul>
 *   <li>Authentication / authorization (check metadata tokens)</li>
 *   <li>Logging / monitoring (record call metrics)</li>
 *   <li>Rate limiting</li>
 *   <li>Request/response transformation</li>
 * </ul>
 * <p>
 * Interceptors apply to the Direct API mode ({@link WireHandlerRegistry});
 * the Provider pipeline mode uses the Jaws Filter chain instead.
 * <p>
 * Thread-safe: implementations must be safe for concurrent invocation.
 *
 * @author shenhongxi
 * @see WireHandlerRegistry#addInterceptor(WireServerInterceptor)
 */
public interface WireServerInterceptor {

    /**
     * Intercept a gRPC call. The interceptor can:
     * <ul>
     *   <li>Call {@code call.next(request)} to continue the chain</li>
     *   <li>Call {@code call.close(error)} to reject the call</li>
     *   <li>Call {@code call.respond(message)} to short-circuit with a response</li>
     * </ul>
     *
     * @param request the decoded protobuf request message
     * @param call    the call facade for interacting with the chain
     */
    void intercept(Message request, Call call);

    /**
     * Facade provided to interceptors for interacting with the call chain.
     */
    interface Call {
        /**
         * @return the per-call context carrying inbound gRPC metadata
         */
        WireCallContext context();

        /**
         * @return the fully-qualified gRPC path (e.g. {@code /service/method})
         */
        String path();

        /**
         * Continue the interceptor chain with the (possibly modified) request.
         * The final result is delivered asynchronously via {@link #respond}
         * or {@link #close}.
         *
         * @param request the request to pass to the next interceptor
         */
        void next(Message request);

        /**
         * Short-circuit the call with a successful response.
         *
         * @param response the protobuf response message
         */
        void respond(Message response);

        /**
         * Short-circuit the call with an error.
         *
         * @param status  the gRPC status code
         * @param message the error description
         */
        void close(int status, String message);
    }
}
