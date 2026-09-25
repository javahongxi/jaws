package org.hongxi.jaws.wire;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.transport.ProviderMessageHandler;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side {@link MessageHandler} that bridges between the raw protobuf
 * bytes used by {@link WireCallDispatcher.ProviderCallDispatcher} and the typed protobuf
 * {@link Message} expected by the Jaws filter chain and {@link Provider}.
 * <p>
 * On the request path, the raw bytes are parsed into a {@code Message} using
 * the {@link com.google.protobuf.Parser} obtained from the service interface.
 * On the response path, the {@code Message} returned by the provider passes
 * through directly — {@code WireCallDispatcher.ProviderCallDispatcher} encodes it into a gRPC frame.
 * <p>
 * One instance serves <b>every</b> wire service exported on a {@code host:port},
 * because the transport deliberately reuses a single server per address: a
 * handler created per export would be discarded on the second export and leave
 * that whole service uncallable. It therefore owns the port's provider registry
 * as well — one address, one handler, one registration step.
 *
 * @author shenhongxi
 */
class WireMessageHandler implements MessageHandler {
    private static final Logger log = LoggerFactory.getLogger(WireMessageHandler.class);

    /** This port's provider registry and invocation dispatch (core side). */
    private final ProviderMessageHandler providers = new ProviderMessageHandler();

    /** Service interface → its protobuf types; iteration order is irrelevant. */
    private final ConcurrentMap<Class<?>, WireProtoTypes> byInterface = new ConcurrentHashMap<>();

    /** Proto service name or Java interface name → types, for exact path matching. */
    private final ConcurrentMap<String, WireProtoTypes> byServiceName = new ConcurrentHashMap<>();

    /**
     * Register one exported service on this port: its provider in the core
     * registry, and its protobuf types for the wire-side decode.
     *
     * @param provider   the exported provider
     * @param protoTypes metadata derived from {@code provider}'s interface
     */
    void addService(Provider<?> provider, WireProtoTypes protoTypes) {
        providers.addProvider(provider);

        Class<?> serviceInterface = provider.getInterface();
        byInterface.put(serviceInterface, protoTypes);
        byServiceName.put(serviceInterface.getName(), protoTypes);
        // A descriptor file can declare services this interface does not own, so
        // never let an alias displace the service that already claimed the name.
        for (String name : protoTypes.getServiceNames()) {
            byServiceName.putIfAbsent(name, protoTypes);
        }
    }

    /**
     * Drop a service that was unexported, leaving the port's other services intact.
     */
    void removeService(Provider<?> provider) {
        providers.removeProvider(provider);

        WireProtoTypes removed = byInterface.remove(provider.getInterface());
        if (removed == null) {
            return;
        }
        byServiceName.remove(provider.getInterface().getName(), removed);
        for (String name : removed.getServiceNames()) {
            byServiceName.remove(name, removed);
        }
    }

    /**
     * @return true once every exported service on this port has been removed
     */
    boolean isEmpty() {
        return byInterface.isEmpty();
    }

    /**
     * Find the protobuf metadata owning a method.
     * <p>
     * The path's service name is tried first; a method-name join is the
     * fallback, because a gRPC caller names the proto service while a Jaws
     * caller names the Java interface — the same ambiguity the provider lookup
     * in {@code AbstractRequestHandler} resolves by method name.
     *
     * @throws IllegalArgumentException when no registered service declares it
     */
    private WireProtoTypes.MethodInfo resolveMethod(String serviceName, String methodName) {
        WireProtoTypes named = serviceName == null ? null : byServiceName.get(serviceName);
        if (named != null && named.hasMethod(methodName)) {
            return named.getMethodInfo(methodName);
        }
        for (WireProtoTypes protoTypes : byInterface.values()) {
            if (protoTypes.hasMethod(methodName)) {
                return protoTypes.getMethodInfo(methodName);
            }
        }
        throw new IllegalArgumentException(
                "No method info registered for: " + methodName);
    }

    @Override
    public CompletableFuture<Object> handleAsync(Object message) {
        if (!(message instanceof Request request)) {
            return providers.handleAsync(message);
        }

        Object[] args = request.getArguments();
        if (args == null || args.length == 0 || !(args[0] instanceof byte[] bytes)) {
            return providers.handleAsync(message);
        }

        // Look up per-method request parser
        WireProtoTypes.MethodInfo methodInfo;
        try {
            methodInfo = resolveMethod(request.getInterfaceName(), request.getMethodName());
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
            return providers.handleAsync(typedRequest);
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
    public StreamSource<Object> handleStream(Request request, StreamSource<Object> requestStream) {
        WireProtoTypes.MethodInfo methodInfo;
        try {
            methodInfo = resolveMethod(request.getInterfaceName(), request.getMethodName());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOperationException("Unknown method: " + request.getMethodName(), e);
        }

        // Wrap the request stream so that handlers see typed protobuf messages
        // instead of the raw byte[] items the transport decodes.
        Parser<? extends Message> requestParser = methodInfo.requestParser();
        StreamSubject<Object> typedStream = new StreamSubject<>();
        requestStream.subscribe(new StreamObserver<Object>() {
            @Override
            public void onNext(Object item) {
                if (item instanceof byte[] bytes) {
                    try {
                        typedStream.onNext(requestParser.parseFrom(bytes));
                    } catch (InvalidProtocolBufferException e) {
                        log.error("Wire stream request item decode failed", e);
                        typedStream.onError(e);
                    }
                } else {
                    typedStream.onNext(item);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                typedStream.onError(throwable);
            }

            @Override
            public void onCompleted() {
                typedStream.onCompleted();
            }
        });

        return providers.handleStream(request, typedStream);
    }

    /**
     * Check if the given method is bidirectional streaming: it consumes a request
     * stream and returns a response stream.
     */
    boolean isBidiStream(String serviceName, String methodName) {
        try {
            WireProtoTypes.MethodInfo info = resolveMethod(serviceName, methodName);
            return info.hasRequestStream() && info.streaming();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Check whether the given method is client-streaming: it consumes a request
     * stream and its response is a single message rather than a source.
     */
    boolean isClientStream(String serviceName, String methodName) {
        try {
            WireProtoTypes.MethodInfo info = resolveMethod(serviceName, methodName);
            return info.hasRequestStream() && !info.streaming();
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
