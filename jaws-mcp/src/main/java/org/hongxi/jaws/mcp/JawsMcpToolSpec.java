package org.hongxi.jaws.mcp;

import org.hongxi.jaws.rpc.Provider;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Holds metadata for a single MCP Tool mapped from a Jaws service method.
 * <p>
 * Each instance represents one public method of a Jaws service interface,
 * containing all information needed to route an MCP tool call back to the
 * Jaws {@link Provider}.
 *
 * @author shenhongxi
 */
public class JawsMcpToolSpec {

    /** The MCP tool name, e.g. "DemoService_getUser" */
    private final String toolName;

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

    /** Method parameters for JSON Schema generation */
    private final Parameter[] parameters;

    public JawsMcpToolSpec(String toolName, String interfaceName, String methodName,
                           String[] parameterTypes, Method method,
                           Provider<?> provider, Parameter[] parameters) {
        this.toolName = toolName;
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.method = method;
        this.provider = provider;
        this.parameters = parameters;
    }

    public String getToolName() {
        return toolName;
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
}
