package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Optional;

/**
 * Utility to extract protobuf {@link Message} types from a service interface.
 * <p>
 * Scans the first declared method of the interface to determine the request
 * message type (first parameter extending {@code Message}) and the response
 * message type (return type extending {@code Message}). Each type's static
 * {@code parser()} method is invoked reflectively to obtain the protobuf
 * {@link Parser}.
 * <p>
 * Convention: every service interface method has exactly one protobuf
 * {@code Message} parameter and returns a protobuf {@code Message}.
 *
 * @author shenhongxi
 */
public final class WireProtoTypes {

    private final Parser<? extends Message> requestParser;
    private final Parser<? extends Message> responseParser;

    private WireProtoTypes(Parser<? extends Message> requestParser,
                           Parser<? extends Message> responseParser) {
        this.requestParser = requestParser;
        this.responseParser = responseParser;
    }

    public Parser<? extends Message> getRequestParser() {
        return requestParser;
    }

    public Parser<? extends Message> getResponseParser() {
        return responseParser;
    }

    /**
     * Extract protobuf types from the first declared method of the given
     * service interface.
     *
     * @param serviceInterface the service interface class
     * @return the extracted types
     * @throws IllegalArgumentException if the interface does not conform to
     *         the protobuf Message convention
     */
    public static WireProtoTypes fromServiceInterface(Class<?> serviceInterface) {
        Method method = findRpcMethod(serviceInterface);

        // Request type: first parameter that extends Message
        Class<?> requestType = null;
        for (Parameter param : method.getParameters()) {
            if (Message.class.isAssignableFrom(param.getType())) {
                requestType = param.getType();
                break;
            }
        }
        if (requestType == null) {
            throw new IllegalArgumentException(
                    "Wire service interface method has no protobuf Message parameter: "
                            + serviceInterface.getName() + "." + method.getName());
        }

        // Response type: return type extending Message
        Class<?> responseType = method.getReturnType();
        if (!Message.class.isAssignableFrom(responseType)) {
            throw new IllegalArgumentException(
                    "Wire service interface method return type is not a protobuf Message: "
                            + serviceInterface.getName() + "." + method.getName()
                            + " returns " + responseType.getName());
        }

        return new WireProtoTypes(resolveParser(requestType), resolveParser(responseType));
    }

    private static Method findRpcMethod(Class<?> serviceInterface) {
        Method[] methods = serviceInterface.getMethods();
        if (methods.length == 0) {
            throw new IllegalArgumentException(
                    "Wire service interface has no methods: " + serviceInterface.getName());
        }
        // Return the first non-default, non-object method
        for (Method m : methods) {
            if (!m.isDefault() && m.getDeclaringClass() == serviceInterface) {
                return m;
            }
        }
        return methods[0];
    }

    /**
     * Reflectively invoke the static {@code parser()} method on a protobuf
     * generated class to obtain its {@link Parser}.
     */
    @SuppressWarnings("unchecked")
    private static Parser<? extends Message> resolveParser(Class<?> messageClass) {
        try {
            Method parserMethod = messageClass.getMethod("parser");
            return (Parser<? extends Message>) parserMethod.invoke(null);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to obtain protobuf parser for: " + messageClass.getName(), e);
        }
    }

    /**
     * Look up the request parser for a specific method name. Currently all
     * methods share the same parser (extracted from the first method), but
     * this API allows future per-method resolution.
     */
    public Optional<Parser<? extends Message>> getRequestParser(String methodName) {
        return Optional.ofNullable(requestParser);
    }
}
