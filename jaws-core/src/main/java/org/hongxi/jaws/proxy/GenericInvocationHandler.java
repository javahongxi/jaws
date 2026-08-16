package org.hongxi.jaws.proxy;

import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.common.util.GenericUtils;
import org.hongxi.jaws.common.util.RequestIdGenerator;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.GenericService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Invocation handler for generic service calls on the consumer side.
 * <p>
 * When a consumer sets {@code generic=true} in {@code ReferenceConfig}, this handler
 * is used instead of {@link ReferenceInvocationHandler}. It builds requests with:
 * <ul>
 *   <li>The real interface name (from {@code serviceInterface} config)</li>
 *   <li>The actual method name and parameter types from the {@code $invoke} call</li>
 *   <li>A {@code $generic=true} attachment to signal generic invocation to the provider</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/7/18.
 *
 * @see GenericService
 */
public class GenericInvocationHandler<T> extends AbstractReferenceHandler<T> implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(GenericInvocationHandler.class);

    public GenericInvocationHandler(String interfaceName, List<Cluster<T>> clusters) {
        super(clusters, interfaceName);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Handle Object methods locally
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "GenericService[" + interfaceName + "]";
                case "hashCode" -> this.clusters == null ? 0 : this.clusters.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new JawsServiceException("can not invoke local method:" + method.getName());
            };
        }

        // Only $invoke is supported for GenericService
        if (!"$invoke".equals(method.getName())) {
            throw new UnsupportedOperationException(
                    "GenericService only supports $invoke method, got: " + method.getName());
        }

        // Extract parameters from $invoke(String method, String[] parameterTypes, Object[] args)
        String methodName = (String) args[0];
        String[] parameterTypes = (String[]) args[1];
        Object[] arguments = (Object[]) args[2];

        // Build the request targeting the real interface
        DefaultRequest request = new DefaultRequest();
        request.setRequestId(RequestIdGenerator.getRequestId());
        request.setInterfaceName(interfaceName);
        request.setMethodName(methodName);
        request.setArguments(arguments);

        // Set parameter description from the type names (empty string for no-arg methods)
        if (parameterTypes != null && parameterTypes.length > 0) {
            request.setParamDesc(GenericUtils.buildParameterDesc(parameterTypes));
        } else {
            request.setParamDesc("");
        }

        // Mark this as a generic invocation so the provider knows to convert parameters
        request.setAttachment("$generic", "true");

        log.debug("[GenericInvoke] interface={}, method={}, paramTypes={}", interfaceName, methodName, parameterTypes);

        // Invoke through the cluster and convert the result for generic response
        Object result = invoke(request, Object.class);
        return GenericUtils.convertResult(result);
    }
}