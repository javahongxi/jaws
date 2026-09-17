package org.hongxi.jaws.wire;

import com.google.protobuf.Message;

/**
 * Intercepts server-side gRPC calls before they reach the business handler.
 * <p>
 * Interceptors form a chain; each interceptor can inspect/modify the request,
 * short-circuit with an error or response, or delegate to the next interceptor.
 * Unlike the previous unary-only design, this interface supports all four gRPC
 * call types: unary, server-streaming, client-streaming, and bidirectional
 * streaming.
 * <p>
 * The interception model follows the grpc-java pattern:
 * <ol>
 *   <li>The interceptor receives a {@link WireServerCall} facade for sending
 *       responses and a {@link WireServerCallHandler} representing the next
 *       element in the chain.</li>
 *   <li>It returns a {@link WireServerListener} that receives inbound request
 *       messages. For unary calls, the framework delivers the single request
 *       via {@link WireServerListener#onMessage(Message)}; for streaming calls,
 *       the request stream is passed directly to the handler.</li>
 *   <li>By wrapping the {@code WireServerCall} (Forwarding pattern), interceptors
 *       can observe or modify outbound responses without short-circuiting.</li>
 * </ol>
 * <p>
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
     *   <li>Call {@code next.startCall(call, request)} to continue the chain</li>
     *   <li>Call {@code call.close(status, message)} to reject the call</li>
     *   <li>Call {@code call.sendMessage(response)} + {@code call.close(0, null)}
     *       to short-circuit with a response</li>
     *   <li>Wrap the {@code call} to observe/modify outbound responses</li>
     *   <li>Wrap the returned {@link WireServerListener} to observe inbound
     *       request messages</li>
     * </ul>
     *
     * @param call    the server call facade for sending responses
     * @param request the decoded protobuf request message (unary/server-stream),
     *                or {@code null} for client-stream/bidi where the request
     *                arrives as a stream
     * @param next    the next handler in the interceptor chain
     * @return a listener that receives inbound request messages
     */
    WireServerListener interceptCall(WireServerCall call, Message request,
                                     WireServerCallHandler next);
}
