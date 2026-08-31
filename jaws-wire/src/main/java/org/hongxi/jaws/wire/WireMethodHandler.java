package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.util.concurrent.Flow;

/**
 * Handles a single gRPC method invocation.
 * <p>
 * Implementations receive a protobuf request {@link Message} and return a
 * protobuf response {@link Message} (unary) or a {@link Flow.Publisher} of
 * messages (server streaming). Both the request and response types must be
 * {@link com.google.protobuf.Message} subclasses — this is the Protobuf
 * Message mode only; the TripleRequestWrapper mode is not supported.
 * <p>
 * Contract for streaming publishers: the framework subscribes only after the
 * provider method returns, so the returned publisher must not drop items
 * emitted before subscription. Note that {@link java.util.concurrent.SubmissionPublisher}
 * drops items submitted while it has no subscribers — defer emission until
 * {@code subscribe()} is called (see the wire provider sample for the safe
 * pattern), or return a cold publisher.
 * <p>
 * Typical usage: implement this interface directly, or use a helper that
 * extracts the handler from protoc-generated service base classes.
 *
 * @author shenhongxi
 */
public interface WireMethodHandler {

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
     * @return a publisher emitting response messages
     */
    default Flow.Publisher<Message> handleStream(Message request) {
        throw new UnsupportedOperationException("Not a streaming method");
    }

    /**
     * Handle a server-streaming gRPC call with the per-call context (inbound
     * metadata). The default implementation delegates to
     * {@link #handleStream(Message)}.
     *
     * @param request the decoded protobuf request message
     * @param context the call context carrying inbound gRPC metadata
     * @return a publisher emitting response messages
     */
    default Flow.Publisher<Message> handleStream(Message request, WireCallContext context) {
        return handleStream(request);
    }

    /**
     * @return the protobuf {@link Parser} for the request message type
     */
    Parser<? extends Message> getRequestParser();
}
