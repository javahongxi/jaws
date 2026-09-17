package org.hongxi.jaws.wire;

import com.google.protobuf.Message;

/**
 * Client-side call facade handed to {@link WireClientInterceptor}s.
 * <p>
 * Provides methods for sending request messages, reading/modifying metadata,
 * and cancelling the call. Interceptors can wrap this interface (Forwarding
 * pattern) to observe or modify outbound requests.
 *
 * @author shenhongxi
 * @see WireClientInterceptor
 */
public interface WireClientCall {

    /**
     * @return the per-call context carrying request metadata
     */
    WireCallContext context();

    /**
     * @return the fully-qualified gRPC path (e.g. {@code /service/method})
     */
    String path();

    /**
     * Add or overwrite a request metadata entry. Must be called before
     * the request is actually sent (i.e. before {@link #sendMessage}).
     *
     * @param key   the metadata key
     * @param value the metadata value
     */
    void putAttachment(String key, String value);

    /**
     * Send the request message. For unary and server-streaming calls, this
     * is invoked exactly once. For client-streaming and bidi, it may be
     * called multiple times.
     *
     * @param request the protobuf request message
     */
    void sendMessage(Message request);

    /**
     * Cancel the call. The server observes the cancellation via RST_STREAM.
     *
     * @param reason a human-readable cancellation reason
     */
    void cancel(String reason);
}
