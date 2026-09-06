package org.hongxi.jaws.transport.http.rest;

import io.netty.handler.codec.http.HttpMethod;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Represents a single REST route mapping from an HTTP method + path pattern
 * to a Java service method.
 * <p>
 * Each mapping carries the metadata needed to dispatch a matched HTTP request
 * into the Jaws RPC pipeline: the target interface name, method name, the
 * reflective {@link Method}, and the parameter bindings that describe how to
 * extract arguments from the HTTP request.
 *
 * @author shenhongxi
 */
public class RestMapping {

    private final HttpMethod httpMethod;
    private final PathPattern pathPattern;
    private final String interfaceName;
    private final String methodName;
    private final Method javaMethod;
    private final List<ParameterBinding> parameterBindings;

    public RestMapping(HttpMethod httpMethod,
                       PathPattern pathPattern,
                       String interfaceName,
                       String methodName,
                       Method javaMethod,
                       List<ParameterBinding> parameterBindings) {
        this.httpMethod = httpMethod;
        this.pathPattern = pathPattern;
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.javaMethod = javaMethod;
        this.parameterBindings = parameterBindings;
    }

    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    public PathPattern getPathPattern() {
        return pathPattern;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public Method getJavaMethod() {
        return javaMethod;
    }

    public List<ParameterBinding> getParameterBindings() {
        return parameterBindings;
    }

    @Override
    public String toString() {
        return httpMethod + " " + pathPattern.getPattern()
                + " → " + interfaceName + "#" + methodName;
    }
}
