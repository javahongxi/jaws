package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Server-side {@link MessageHandler} that bridges between the raw protobuf
 * bytes used by {@link WireCallDispatcher.ProviderCallDispatcher} and the typed protobuf
 * {@link Message} expected by the Jaws filter chain and {@link org.hongxi.jaws.rpc.Provider}.
 * <p>
 * On the request path, the raw bytes are parsed into a {@code Message} using
 * the {@link com.google.protobuf.Parser} obtained from the service interface.
 * On the response path, the {@code Message} returned by the provider passes
 * through directly — {@code WireCallDispatcher.ProviderCallDispatcher} encodes it into a gRPC frame.
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
    public CompletableFuture<Object> handleAsync(Object message) {
        if (!(message instanceof Request request)) {
            return delegate.handleAsync(message);
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof byte[] bytes)) {
            return delegate.handleAsync(message);
        }

        // Look up per-method request parser
        WireProtoTypes.MethodInfo methodInfo;
        try {
            methodInfo = protoTypes.getMethodInfo(request.getMethodName());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }

        try {
            // Parse raw protobuf bytes into typed Message
            Message requestMessage = methodInfo.requestParser().parseFrom(bytes);

            // Build a new request with the typed Message as argument.
            // Convert gRPC PascalCase method name (SayHello) back to Java
            // camelCase (sayHello) so that the Provider can find the method.
            DefaultRequest typedRequest = new DefaultRequest();
            typedRequest.setInterfaceName(request.getInterfaceName());
            typedRequest.setMethodName(toJavaMethodName(request.getMethodName()));
            typedRequest.setParamDesc(request.getParamDesc());
            typedRequest.setArguments(new Object[]{requestMessage});
            typedRequest.setRequestId(request.getRequestId());
            typedRequest.setRetries(request.getRetries());
            for (var entry : request.getAttachments().entrySet()) {
                typedRequest.setAttachment(entry.getKey(), entry.getValue());
            }

            // Delegate to the filter chain / provider.
            // For unary: the response Message passes through directly.
            // For streaming: the response is a StreamSource, passed through as-is.
            // WireCallDispatcher.ProviderCallDispatcher handles gRPC frame encoding for both cases.
            return delegate.handleAsync(typedRequest);
        } catch (InvalidProtocolBufferException e) {
            log.error("Wire message decode failed: interface={} method={}",
                    request.getInterfaceName(), request.getMethodName(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Handle a streaming request: wrap the incoming {@code requestStream}
     * to convert {@code byte[]} items to typed protobuf {@link Message}
     * instances, then delegate to the filter chain.
     */
    @Override
    public StreamSource<Object> handleStream(Request request, StreamObserver<Object> requestStream) {
        WireProtoTypes.MethodInfo methodInfo;
        try {
            methodInfo = protoTypes.getMethodInfo(request.getMethodName());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOperationException("Unknown method: " + request.getMethodName(), e);
        }

        // Wrap the request stream to convert byte[] items → typed Messages.
        // In practice requestStream is always a StreamSubject (which
        // implements both StreamObserver and StreamSource), so we can subscribe
        // to it when it's also a StreamSource.
        Parser<? extends Message> requestParser = methodInfo.requestParser();
        StreamSubject<Object> typedObserver = new StreamSubject<>();
        if (requestStream instanceof StreamSource<?>) {
            //noinspection unchecked
            StreamSource<Object> source = (StreamSource<Object>) requestStream;
            source.subscribe(new StreamObserver<>() {
                @Override
                public void onNext(Object item) {
                    if (item instanceof byte[] bytes) {
                        try {
                            Message msg = requestParser.parseFrom(bytes);
                            typedObserver.onNext(msg);
                        } catch (InvalidProtocolBufferException e) {
                            log.error("Wire bidi stream item decode failed", e);
                            typedObserver.onError(e);
                        }
                    } else {
                        typedObserver.onNext(item);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    typedObserver.onError(throwable);
                }

                @Override
                public void onCompleted() {
                    typedObserver.onCompleted();
                }
            });
        }

        return delegate.handleStream(request, typedObserver);
    }

    /**
     * Check if the given method is a bidirectional streaming method.
     */
    boolean isBiStreaming(String methodName) {
        try {
            return protoTypes.getMethodInfo(methodName).biStreaming();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Check whether the given method is client-streaming:
     * has a {@code StreamObserver} parameter but a non-Source return type.
     */
    boolean isClientStreaming(String methodName) {
        try {
            WireProtoTypes.MethodInfo info = protoTypes.getMethodInfo(methodName);
            return info.biStreaming() && !info.streaming();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Convert a gRPC PascalCase method name (e.g. {@code SayHello}) back to
     * Java camelCase (e.g. {@code sayHello}) for provider method lookup.
     */
    private static String toJavaMethodName(String grpcMethodName) {
        if (grpcMethodName == null || grpcMethodName.isEmpty()) {
            return grpcMethodName;
        }
        return Character.toLowerCase(grpcMethodName.charAt(0)) + grpcMethodName.substring(1);
    }
}
