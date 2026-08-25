package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.transport.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Server-side {@link MessageHandler} that bridges between the raw protobuf
 * bytes used by {@link WireSpiServerStreamHandler} and the typed protobuf
 * {@link Message} expected by the Jaws filter chain and {@link org.hongxi.jaws.rpc.Provider}.
 * <p>
 * On the request path, the raw bytes are parsed into a {@code Message} using
 * the {@link Parser} obtained from the service interface. On the response path,
 * the {@code Message} returned by the provider passes through directly —
 * {@code WireSpiServerStreamHandler} encodes it into a gRPC frame.
 *
 * @author shenhongxi
 */
class WireMessageHandler implements MessageHandler {
    private static final Logger log = LoggerFactory.getLogger(WireMessageHandler.class);

    private final MessageHandler delegate;
    private final WireProtoTypes protoTypes;

    WireMessageHandler(MessageHandler delegate, WireProtoTypes protoTypes) {
        this.delegate = delegate;
        this.protoTypes = protoTypes;
    }

    @Override
    public CompletableFuture<Object> handleAsync(org.hongxi.jaws.transport.Channel channel,
                                                 Object message) {
        if (!(message instanceof Request request)) {
            return delegate.handleAsync(channel, message);
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof byte[] bytes)) {
            return delegate.handleAsync(channel, message);
        }

        // Look up per-method request parser
        WireProtoTypes.MethodInfo methodInfo;
        try {
            methodInfo = protoTypes.getMethodInfo(request.getMethodName());
        } catch (IllegalArgumentException e) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }

        try {
            // Parse raw protobuf bytes into typed Message
            Message requestMessage = methodInfo.requestParser().parseFrom(bytes);

            // Build a new request with the typed Message as argument
            DefaultRequest typedRequest = new DefaultRequest();
            typedRequest.setInterfaceName(request.getInterfaceName());
            typedRequest.setMethodName(request.getMethodName());
            typedRequest.setParamDesc(request.getParamDesc());
            typedRequest.setArguments(new Object[]{requestMessage});
            typedRequest.setRequestId(request.getRequestId());
            typedRequest.setRetries(request.getRetries());
            for (var entry : request.getAttachments().entrySet()) {
                typedRequest.setAttachment(entry.getKey(), entry.getValue());
            }

            // Delegate to the filter chain / provider.
            // For unary: the response Message passes through directly.
            // For streaming: the response is a Flow.Publisher, passed through as-is.
            // WireSpiServerStreamHandler handles gRPC frame encoding for both cases.
            return delegate.handleAsync(channel, typedRequest);
        } catch (InvalidProtocolBufferException e) {
            log.error("Wire message decode failed: interface={} method={}",
                    request.getInterfaceName(), request.getMethodName(), e);
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }
}
