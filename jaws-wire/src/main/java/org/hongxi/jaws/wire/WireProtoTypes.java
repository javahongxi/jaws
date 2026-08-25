package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Utility to extract protobuf {@link Message} types from a service interface.
 * <p>
 * Scans all declared methods of the interface to build per-method metadata:
 * the request message type (first parameter extending {@code Message}) and the
 * response message type (return type extending {@code Message}, or the generic
 * type argument of {@code Flow.Publisher<Message>} for server-streaming methods).
 * Each type's static {@code parser()} method is invoked reflectively to obtain
 * the protobuf {@link Parser}.
 * <p>
 * Convention: every service interface method has exactly one protobuf
 * {@code Message} parameter and returns either a protobuf {@code Message}
 * (unary) or a {@code Flow.Publisher<Message>} (server streaming).
 *
 * @author shenhongxi
 */
public final class WireProtoTypes {

    private final Map<String, MethodInfo> methodInfoMap;

    private WireProtoTypes(Map<String, MethodInfo> methodInfoMap) {
        this.methodInfoMap = methodInfoMap;
    }

    /**
     * Per-method protobuf type metadata.
     */
    public record MethodInfo(Parser<? extends Message> requestParser,
                             Parser<? extends Message> responseParser,
                             boolean streaming) {}

    /**
     * @return the method info for the given method name
     * @throws IllegalArgumentException if no info is registered for the method
     */
    public MethodInfo getMethodInfo(String methodName) {
        MethodInfo info = methodInfoMap.get(methodName);
        if (info == null) {
            throw new IllegalArgumentException(
                    "No method info registered for: " + methodName);
        }
        return info;
    }

    /**
     * @return the response parser for the single-method case (backward compatible)
     */
    public Parser<? extends Message> getResponseParser() {
        if (methodInfoMap.size() != 1) {
            throw new IllegalStateException(
                    "getResponseParser() requires exactly one method, but found: " + methodInfoMap.size());
        }
        return methodInfoMap.values().iterator().next().responseParser();
    }

    /**
     * Extract protobuf types from all declared methods of the given service interface.
     *
     * @param serviceInterface the service interface class
     * @return the extracted types
     * @throws IllegalArgumentException if the interface does not conform to
     *         the protobuf Message convention
     */
    public static WireProtoTypes fromServiceInterface(Class<?> serviceInterface) {
        Method[] methods = serviceInterface.getMethods();
        if (methods.length == 0) {
            throw new IllegalArgumentException(
                    "Wire service interface has no methods: " + serviceInterface.getName());
        }

        Map<String, MethodInfo> map = new HashMap<>();
        for (Method method : methods) {
            if (method.isDefault() || method.getDeclaringClass() != serviceInterface) {
                continue;
            }

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

            // Response type: return type extending Message, or Flow.Publisher<Message>
            boolean streaming = false;
            Class<?> responseType;
            if (Flow.Publisher.class.isAssignableFrom(method.getReturnType())) {
                streaming = true;
                responseType = resolvePublisherTypeArgument(method);
            } else if (Message.class.isAssignableFrom(method.getReturnType())) {
                responseType = method.getReturnType();
            } else {
                throw new IllegalArgumentException(
                        "Wire service interface method return type must be Message or Flow.Publisher<Message>: "
                                + serviceInterface.getName() + "." + method.getName()
                                + " returns " + method.getReturnType().getName());
            }

            map.put(method.getName(), new MethodInfo(
                    resolveParser(requestType), resolveParser(responseType), streaming));
        }

        return new WireProtoTypes(map);
    }

    /**
     * Resolve the type argument of {@code Flow.Publisher<T>} from the method's
     * generic return type.
     */
    private static Class<?> resolvePublisherTypeArgument(Method method) {
        Type genericReturn = method.getGenericReturnType();
        if (genericReturn instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> clazz
                    && Message.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        }
        throw new IllegalArgumentException(
                "Cannot resolve Flow.Publisher type argument for streaming method: "
                        + method.getDeclaringClass().getName() + "." + method.getName()
                        + ". The type argument must be a concrete protobuf Message class.");
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
}
