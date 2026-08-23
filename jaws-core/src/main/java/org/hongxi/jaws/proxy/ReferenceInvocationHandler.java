package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Reference;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * JDK dynamic proxy {@link InvocationHandler} that turns local interface
 * method calls into remote {@link DefaultRequest} invocations dispatched
 * through the {@link Cluster} layer.
 * <p>
 * Methods declared only on {@code Object} (toString, equals, hashCode) are
 * handled locally unless the interface re-declares them, and methods
 * returning {@link CompletableFuture} are invoked asynchronously.
 *
 * @see AbstractReferenceHandler
 * @see JdkProxyFactory
 *
 * <p>
 * Created by shenhongxi on 2021/4/23.
 */
public class ReferenceInvocationHandler<T> extends AbstractReferenceHandler<T> implements InvocationHandler {

    private final Class<T> interfaceClass;

    public ReferenceInvocationHandler(Class<T> interfaceClass, List<Cluster<T>> clusters) {
        super(clusters, interfaceClass.getName());
        this.interfaceClass = interfaceClass;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (isLocalMethod(method)) {
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
     * Methods not declared in the interface (toString, equals, hashCode, etc.) are not invoked remotely
     */
    private boolean isLocalMethod(Method method) {
        if (method.getDeclaringClass().equals(Object.class)) {
            for (Method m : interfaceClass.getMethods()) {
                if (m.getName().equals(method.getName())
                        && Arrays.equals(m.getParameterTypes(), method.getParameterTypes())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private String clustersToString() {
        StringBuilder sb = new StringBuilder();
        for (Cluster<T> cluster : clusters) {
            sb.append("{protocol:").append(cluster.getUrl().getProtocol());
            List<Reference<T>> references = cluster.getReferences();
            if (references != null) {
                for (Reference<T> refer : references) {
                    sb.append("[").append(refer.getUrl().toSimpleString()).append(", available:").append(refer.isAvailable()).append("]");
                }
            }
            sb.append("}");
        }
        return sb.toString();
    }
}