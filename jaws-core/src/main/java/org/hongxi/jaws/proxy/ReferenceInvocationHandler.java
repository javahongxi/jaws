package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 * @see AbstractReferenceHandler
 * @see JdkProxyFactory
 *
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
public class ReferenceInvocationHandler<T> extends AbstractReferenceHandler<T> implements InvocationHandler {

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
        request.setArguments(args);
        request.setMethodName(method.getName());
        request.setParamDesc(ReflectUtils.getMethodParamDesc(method));
        request.setInterfaceName(interfaceName);

        if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
            return invokeAsync(request, method.getReturnType());
        }
        return invoke(request, method.getReturnType());
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