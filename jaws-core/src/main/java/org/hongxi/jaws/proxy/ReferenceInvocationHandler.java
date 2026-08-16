package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.common.util.RequestIdGenerator;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Reference;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
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
                default -> throw new JawsServiceException("can not invoke local method:" + method.getName());
            };
        }

        DefaultRequest request = new DefaultRequest();
        request.setRequestId(RequestIdGenerator.getRequestId());
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
     * toString,equals,hashCode 等接口未声明的方法不进行远程调用
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