package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

/**
 * Handles a single gRPC method invocation.
 * <p>
 * Implementations receive a protobuf request {@link Message} and return a
 * protobuf response {@link Message} (unary) or a {@link StreamSource} of
 * messages (server streaming). Both the request and response types must be
 * {@link com.google.protobuf.Message} subclasses — this is the Protobuf
 * Message mode only; the TripleRequestWrapper mode is not supported.
 * <p>
 * Contract for streaming sources: the framework subscribes only after the
 * provider method returns, so the returned source must not drop items
 * emitted before subscription. Use {@link org.hongxi.jaws.transport.StreamSubject}
 * to buffer items until the framework subscribes (see the wire provider sample
 * for the pattern).
 * <p>
 * Typical usage: implement this interface directly, or use a helper that
 * extracts the handler from protoc-generated service base classes.
 *
 * @author shenhongxi
 */
public interface WireMethodHandler {

    /**
     * The invocation style of a gRPC method, mirroring the proto definition.
     */
    enum MethodType {
        UNARY,
        SERVER_STREAMING,
        CLIENT_STREAMING,
        BIDIRECTIONAL
    }

    /**
     * @return the invocation style of this method; defaults to {@link MethodType#UNARY}.
     *         Streaming handlers must override and return the appropriate
     *         {@link MethodType#SERVER_STREAMING}, {@link MethodType#CLIENT_STREAMING},
     *         or {@link MethodType#BIDIRECTIONAL}.
     */
    default MethodType methodType() {
        return MethodType.UNARY;
    }

    /**
     * Handle a unary gRPC call.
     *
     * @param request the decoded protobuf request message
     * @return the protobuf response message
     */
    Message handle(Message request);

    /**
     * Handle a unary gRPC call with the per-call context (inbound metadata).
     * The default implementation delegates to {@link #handle(Message)} for
     * handlers that do not need the metadata.
     *
     * @param request the decoded protobuf request message
     * @param context the call context carrying inbound gRPC metadata
     * @return the protobuf response message
     */
    default Message handle(Message request, WireCallContext context) {
        return handle(request);
    }

    /**
     * Handle a server-streaming gRPC call. The default implementation throws
     * {@link UnsupportedOperationException}; override for streaming methods.
     *
     * @param request the decoded protobuf request message
     * @return a source emitting response messages
     */
    default StreamSource<Message> handleStream(Message request) {
        throw new UnsupportedOperationException("Not a streaming method");
    }

    /**
     * Handle a server-streaming gRPC call with the per-call context (inbound
     * metadata). The default implementation delegates to
     * {@link #handleStream(Message)}.
     *
     * @param request the decoded protobuf request message
     * @param context the call context carrying inbound gRPC metadata
     * @return a source emitting response messages
     */
    default StreamSource<Message> handleStream(Message request, WireCallContext context) {
        return handleStream(request);
    }

    /**
     * Handle a client-streaming gRPC call: receives a publisher of request
     * messages and returns a single response message. The default
     * implementation throws {@link UnsupportedOperationException}; override
     * for client-streaming methods.
     *
     * @param requestStream the client's request messages, consumed by subscribing
     * @return the protobuf response message
     */
    default Message handleClientStream(StreamSource<Message> requestStream) {
        throw new UnsupportedOperationException("Not a client-streaming method");
    }

    /**
     * Handle a client-streaming gRPC call with the per-call context (inbound
     * metadata). The default implementation delegates to
     * {@link #handleClientStream(StreamSource)}.
     *
     * @param requestStream the client's request messages, consumed by subscribing
     * @param context       the call context carrying inbound gRPC metadata
     * @return the protobuf response message
     */
    default Message handleClientStream(StreamSource<Message> requestStream, WireCallContext context) {
        return handleClientStream(requestStream);
    }

    /**
     * Handle a bidirectional streaming gRPC call: receives a publisher of
     * request messages and returns a publisher of response messages.
     * The default implementation throws {@link UnsupportedOperationException};
     * override for bidirectional streaming methods.
     *
     * @param requestStream the client's request messages, consumed by subscribing
     * @return a source emitting response messages
     */
    default StreamSource<Message> handleBiStream(StreamSource<Message> requestStream) {
        throw new UnsupportedOperationException("Not a bidirectional streaming method");
    }

    /**
     * Handle a bidirectional streaming gRPC call with the per-call context
     * (inbound metadata). The default implementation delegates to
     * {@link #handleBiStream(StreamSource)}.
     *
     * @param requestStream the client's request messages, consumed by subscribing
     * @param context       the call context carrying inbound gRPC metadata
     * @return a source emitting response messages
     */
    default StreamSource<Message> handleBiStream(StreamSource<Message> requestStream, WireCallContext context) {
        return handleBiStream(requestStream);
    }

    /**
     * @return the protobuf {@link Parser} for the request message type
     */
    Parser<? extends Message> getRequestParser();
}
