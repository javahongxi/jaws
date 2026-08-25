package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
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
    private final Parser<? extends Message> requestParser;

    WireMessageHandler(MessageHandler delegate, Parser<? extends Message> requestParser) {
        this.delegate = delegate;
        this.requestParser = requestParser;
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

        try {
            // Parse raw protobuf bytes into typed Message
            Message requestMessage = requestParser.parseFrom(bytes);

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
            // The response Message passes through without conversion —
            // WireSpiServerStreamHandler handles gRPC frame encoding.
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
