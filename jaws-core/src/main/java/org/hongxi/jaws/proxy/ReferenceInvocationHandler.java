package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.util.ExceptionUtils;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.http2.Http2Constants;
import org.hongxi.jaws.transport.http2.StreamType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * JDK dynamic proxy {@link InvocationHandler} that turns local interface
 * method calls into remote {@link DefaultRequest} invocations dispatched
 * through the {@link Cluster} layer.
 * <p>
 * Methods matching the {@code Object} signatures (toString, equals, hashCode)
 * are always handled locally, even if the interface re-declares them, and
 * methods returning {@link CompletableFuture} are invoked asynchronously.
 *
 * @see ReferenceInvoker
 * @see JdkProxyFactory
 *
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
public class ReferenceInvocationHandler<T> extends ReferenceInvoker<T> implements InvocationHandler {

    public ReferenceInvocationHandler(Class<T> interfaceClass, List<Cluster<T>> clusters) {
        super(clusters, interfaceClass.getName());
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (isObjectMethod(method)) {
            return switch (method.getName()) {
                case "toString" -> clustersToString();
                case "equals" -> proxy == args[0];
                case "hashCode" -> this.clusters == null ? 0 : this.clusters.hashCode();
                default -> throw new JawsServiceException("cannot invoke local method: " + method.getName());
            };
        }

        DefaultRequest request = new DefaultRequest();
        request.setInterfaceName(interfaceName);
        request.setMethodName(method.getName());
        request.setParamDesc(ReflectUtils.getMethodParamDesc(method));
        request.setArguments(args);

        if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
            return invokeAsync(request);
        }

        // Streaming detection
        int observerIndex = findObserverArgIndex(method, args);
        boolean returnsSource = StreamSource.class.isAssignableFrom(method.getReturnType());

        if (observerIndex >= 0 && returnsSource) {
            // Bidi streaming: StreamObserver param + StreamSource return
            //noinspection unchecked
            return invokeBidiStream(request, (StreamObserver<Object>) args[observerIndex], args, observerIndex);
        }
        if (returnsSource) {
            // Server streaming: StreamSource return, no StreamObserver param
            return invokeStream(request);
        }
        if (observerIndex >= 0) {
            // Client streaming: StreamObserver param, non-StreamSource return
            //noinspection unchecked
            return invokeClientStream(request, (StreamObserver<Object>) args[observerIndex], args, observerIndex);
        }

        return invoke(request, method.getReturnType());
    }

    /**
     * Find the index of the first {@link StreamObserver} argument in the method
     * parameters. Returns {@code -1} if no StreamObserver parameter is found.
     */
    private static int findObserverArgIndex(Method method, Object[] args) {
        if (args == null) {
            return -1;
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (StreamObserver.class.isAssignableFrom(paramTypes[i]) && args[i] instanceof StreamObserver) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Bidirectional streaming: strip the StreamObserver arg and invoke with the
     * request stream, returning a StreamSource to the caller.
     */
    private Object invokeBidiStream(DefaultRequest request, StreamObserver<Object> observer,
                                    Object[] args, int observerIndex) throws Throwable {
        request.setArguments(stripArg(args, observerIndex));
        request.setAttachment(Http2Constants.HEADER_STREAMING, StreamType.BIDIRECTIONAL.getValue());
        return invokeStream(request, observer);
    }

    /**
     * Client streaming: strip the StreamObserver arg, send the request stream, and
     * block for the single response value.
     */
    private Object invokeClientStream(DefaultRequest request, StreamObserver<Object> observer,
                                      Object[] args, int observerIndex) throws Throwable {
        request.setArguments(stripArg(args, observerIndex));
        request.setAttachment(Http2Constants.HEADER_STREAMING, StreamType.CLIENT.getValue());
        return blockForClientStream(request, observer);
    }

    private static Object[] stripArg(Object[] args, int index) {
        Object[] remaining = new Object[args.length - 1];
        int idx = 0;
        for (int j = 0; j < args.length; j++) {
            if (j != index) {
                remaining[idx++] = args[j];
            }
        }
        return remaining;
    }

    /**
     * Client streaming: send the request observer and block for the single
     * response value from the returned StreamSource.
     */
    private Object blockForClientStream(DefaultRequest request, StreamObserver<Object> requestObserver) throws Throwable {
        StreamSource<Object> responseSource = invokeStream(request, requestObserver);
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        responseSource.subscribe(new StreamObserver<>() {
            @Override
            public void onNext(Object item) {
                resultFuture.complete(item);
            }
            @Override
            public void onError(Throwable throwable) {
                resultFuture.completeExceptionally(throwable);
            }
            @Override
            public void onCompleted() {
                resultFuture.complete(null);
            }
        });
        try {
            return resultFuture.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                if (ExceptionUtils.isBizException(ex)) {
                    Throwable t = ex.getCause();
                    if (t instanceof Exception inner) {
                        throw inner;
                    }
                    throw new JawsServiceException("biz exception in client streaming call: " + ex.getMessage());
                }
                throw ex;
            }
            throw new JawsServiceException("client streaming call failed", cause);
        }
    }

    /**
     * toString, equals and hashCode carry local object semantics and are never
     * invoked remotely, even if the interface re-declares them.
     */
    private boolean isObjectMethod(Method method) {
        return switch (method.getName()) {
            case "toString", "hashCode" -> method.getParameterCount() == 0;
            case "equals" -> method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Object.class;
            default -> false;
        };
    }

    private String clustersToString() {
        if (clusters == null || clusters.isEmpty()) {
            return interfaceName;
        }
        return clusters.stream()
                .map(cluster -> cluster.getUrl().toSimpleString())
                .collect(Collectors.joining(", ", interfaceName + " [", "]"));
    }
}