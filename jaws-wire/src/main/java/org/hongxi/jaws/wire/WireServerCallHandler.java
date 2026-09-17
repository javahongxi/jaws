package org.hongxi.jaws.wire;

import com.google.protobuf.Message;

/**
 * Handler for a server-side gRPC call, representing the next element in the
 * interceptor chain. The terminal implementation invokes the actual business
 * {@link WireMethodHandler}.
 * <p>
 * This is the server-side counterpart of grpc-java's {@code ServerCallHandler}.
 * Interceptors receive this as the {@code next} parameter and call
 * {@link #startCall} to delegate to the next interceptor or the business handler.
 *
 * @author shenhongxi
 * @see WireServerInterceptor
 */
public interface WireServerCallHandler {

    /**
     * Start the call, returning a listener that receives inbound request
     * messages. For unary calls, the framework delivers the request via
     * {@link WireServerListener#onMessage(Message)}; for streaming calls,
     * the request stream is handled by the underlying handler.
     *
     * @param call    the server call facade for sending responses
     * @param request the decoded protobuf request message, or {@code null}
     *                for client-stream/bidi where the request arrives as a
     *                stream
     * @return a listener for inbound request messages
     */
    WireServerListener startCall(WireServerCall call, Message request);
}
