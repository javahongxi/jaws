package org.hongxi.jaws.mcp.bridge;

import org.hongxi.jaws.rpc.Provider;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.Flow;

/**
 * Holds metadata for a single service method, shared across protocol bridges
 * (MCP, REST, etc.).
 * <p>
 * Each instance represents one public method of a Jaws service interface,
 * containing all information needed to route an external call back to the
 * Jaws {@link Provider}.
 *
 * @author shenhongxi
 */
public class ServiceMethodSpec {

    /** The external tool/action name, e.g. "DemoService_getUser" */
    private final String actionName;

    /** The Jaws service interface name (fully qualified) */
    private final String interfaceName;

    /** The method name */
    private final String methodName;

    /** Parameter type names for method resolution (Jaws parameterDesc format) */
    private final String[] parameterTypes;

    /** The Java Method object for argument conversion */
    private final Method method;

    /** The Jaws Provider that can execute this method */
    private final Provider<?> provider;

    /** Method parameters for schema generation */
    private final Parameter[] parameters;

    public ServiceMethodSpec(String actionName, String interfaceName, String methodName,
                             String[] parameterTypes, Method method,
                             Provider<?> provider, Parameter[] parameters) {
        this.actionName = actionName;
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.method = method;
        this.provider = provider;
        this.parameters = parameters;
    }

    public String getActionName() {
        return actionName;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public String[] getParameterTypes() {
        return parameterTypes;
    }

    public Method getMethod() {
        return method;
    }

    public Provider<?> getProvider() {
        return provider;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    /**
     * Whether this method involves streaming (has {@link Flow.Publisher} as
     * parameter type or return type).  Streaming methods are not suitable for
     * MCP tool registration since MCP tools are unary request-response.
     */
    public boolean isStreamingMethod() {
        if (Flow.Publisher.class.isAssignableFrom(method.getReturnType())) {
            return true;
        }
        for (Class<?> paramType : method.getParameterTypes()) {
            if (Flow.Publisher.class.isAssignableFrom(paramType)) {
                return true;
            }
        }
        return false;
    }
}
