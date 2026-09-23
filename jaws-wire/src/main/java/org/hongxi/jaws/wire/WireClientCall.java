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
     * Add or overwrite a request metadata entry. Must be called before the
     * HEADERS go out — i.e. before the first {@link #sendMessage} or
     * {@link #halfClose}.
     *
     * @param key   the metadata key
     * @param value the metadata value
     */
    void putAttachment(String key, String value);

    /**
     * Send one request message. The first call to {@code sendMessage} or
     * {@link #halfClose} also flushes the request HEADERS, so any metadata an
     * interceptor injected is folded in before it reaches the wire.
     * <p>
     * Unary and server-streaming call this exactly once (the single request);
     * client-streaming and bidi call it once per request item, then
     * {@link #halfClose}. Because every item is routed through the call, an
     * interceptor wrapping it observes each outbound message, not just the
     * opening metadata.
     *
     * @param request the protobuf request message
     */
    void sendMessage(Message request);

    /**
     * Half-close the request stream: signal that the client has sent its last
     * message (HTTP/2 END_STREAM), which is what lets a server-streaming or
     * unary response be produced and a client-streaming method finish
     * accumulating. Mirrors {@code io.grpc.ClientCall#halfClose}. The call is
     * still open for inbound responses after this; use {@link #cancel} to abort.
     */
    void halfClose();

    /**
     * Cancel the call. The server observes the cancellation via RST_STREAM.
     *
     * @param reason a human-readable cancellation reason
     */
    void cancel(String reason);
}
