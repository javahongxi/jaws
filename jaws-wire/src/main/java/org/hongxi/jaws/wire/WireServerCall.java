package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import org.hongxi.jaws.stream.StreamSource;

/**
 * Server-side call facade handed to {@link WireServerInterceptor}s.
 * <p>
 * Provides methods for sending response messages, streaming responses,
 * closing the call with a status, and reading per-call metadata.
 * <p>
 * Interceptors can wrap this interface (Forwarding pattern) to observe
 * or modify outbound responses without short-circuiting the call.
 *
 * @author shenhongxi
 * @see WireServerInterceptor
 */
public interface WireServerCall {

    /**
     * @return the per-call context carrying inbound gRPC metadata
     */
    WireCallContext context();

    /**
     * @return the fully-qualified gRPC path (e.g. {@code /service/method})
     */
    String path();

    /**
     * Send a single response message. For unary calls, exactly one message
     * is sent before {@link #close}. For server-streaming calls, multiple
     * messages may be sent.
     *
     * @param response the protobuf response message
     */
    void sendMessage(Message response);

    /**
     * Dispatch a streaming response source. The framework subscribes to the
     * source and emits each message as a gRPC DATA frame. Used by interceptors
     * that need to replace or wrap the response stream (e.g. server-streaming
     * or bidi calls).
     *
     * @param source the response stream source
     */
    void dispatchStream(StreamSource<Message> source);

    /**
     * Close the call with the given gRPC status and optional message.
     * After this call, no further messages can be sent.
     *
     * @param status  the gRPC status code (0 = OK)
     * @param message the error description, or {@code null} for OK
     */
    void close(int status, String message);

    /**
     * @return {@code true} if the caller has cancelled the stream (RST_STREAM)
     */
    boolean isCancelled();
}
