package org.hongxi.jaws.wire;

import com.google.protobuf.Message;

/**
 * Listener for inbound request messages on the server side, returned by
 * {@link WireServerInterceptor#interceptCall} and
 * {@link WireServerCallHandler#startCall}.
 * <p>
 * For unary and server-streaming calls, the framework delivers the single
 * request via {@link #onMessage(Message)}, followed by {@link #onHalfClose()}
 * when the client has finished sending.
 * <p>
 * For client-streaming and bidirectional calls, request items flow through
 * the {@link org.hongxi.jaws.stream.StreamSource} passed to the handler;
 * the listener only receives {@link #onHalfClose()} when the client finishes.
 *
 * @author shenhongxi
 * @see WireServerInterceptor
 */
public interface WireServerListener {

    /**
     * Called when a request message is received. For unary and server-streaming
     * calls, this is invoked exactly once with the decoded request.
     *
     * @param message the decoded protobuf request message
     */
    default void onMessage(Message message) {}

    /**
     * Called when the client has finished sending request messages
     * (END_STREAM received). For unary calls, this triggers the handler
     * invocation after {@link #onMessage(Message)}.
     */
    default void onHalfClose() {}

    /**
     * Called when the caller cancels the stream (RST_STREAM).
     */
    default void onCancel() {}
}
