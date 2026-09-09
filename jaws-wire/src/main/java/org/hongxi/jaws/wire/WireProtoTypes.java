package org.hongxi.jaws.wire;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;

/**
 * Utility to extract protobuf {@link Message} types from a service interface.
 * <p>
 * Scans all declared methods of the interface to build per-method metadata:
 * the request message type (first parameter extending {@code Message}, or the
 * type argument of a {@code StreamObserver<Message>} parameter for bidirectional
 * streaming) and the response message type (return type extending {@code Message},
 * or the generic type argument of {@code StreamSource<Message>} for server-
 * streaming methods). Each type's static {@code parser()} method is invoked
 * reflectively to obtain the protobuf {@link Parser}.
 * <p>
 * Convention: every service interface method has either a protobuf {@code Message}
 * parameter (unary / server streaming) or a {@code StreamObserver<Message>}
 * parameter (bidirectional streaming), and returns either a protobuf {@code Message}
 * (unary) or a {@code StreamSource<Message>} (streaming).
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
     *
     * @param biStreaming true when the method has a {@code StreamObserver} parameter
     *                    (bidirectional streaming), meaning the request type is
     *                    extracted from the observer's type argument
     */
    public record MethodInfo(Class<? extends Message> requestClass,
                             Class<? extends Message> responseClass,
                             Parser<? extends Message> requestParser,
                             Parser<? extends Message> responseParser,
                             boolean streaming,
                             boolean biStreaming) {}

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

            // Request type: first parameter that extends Message, or the type
            // argument of a StreamObserver parameter (bidirectional streaming)
            Class<?> requestType = null;
            boolean biStreaming = false;
            for (Parameter param : method.getParameters()) {
                if (Message.class.isAssignableFrom(param.getType())) {
                    requestType = param.getType();
                    break;
                }
                if (StreamObserver.class.isAssignableFrom(param.getType())) {
                    requestType = resolveStreamObserverParamType(method, param);
                    biStreaming = true;
                    break;
                }
            }
            if (requestType == null) {
                throw new IllegalArgumentException(
                        "Wire service interface method has no protobuf Message or StreamObserver<Message> parameter: "
                                + serviceInterface.getName() + "." + method.getName());
            }

            // Response type: return type extending Message, or StreamSource<Message>
            boolean streaming = false;
            Class<?> responseType;
            if (StreamSource.class.isAssignableFrom(method.getReturnType())) {
                streaming = true;
                responseType = resolveStreamSourceTypeArgument(method);
            } else if (Message.class.isAssignableFrom(method.getReturnType())) {
                responseType = method.getReturnType();
            } else {
                throw new IllegalArgumentException(
                        "Wire service interface method return type must be Message or StreamSource<Message>: "
                                + serviceInterface.getName() + "." + method.getName()
                                + " returns " + method.getReturnType().getName());
            }

            MethodInfo info = new MethodInfo(
                    asMessageClass(requestType), asMessageClass(responseType),
                    resolveParser(requestType), resolveParser(responseType), streaming, biStreaming);
            // Register under the Java method name (camelCase: sayHello)
            map.put(method.getName(), info);
            // Also register under the gRPC method name (PascalCase: SayHello)
            // so that lookups from the gRPC path /service/Method succeed.
            map.put(toGrpcMethodName(method.getName()), info);
        }

        return new WireProtoTypes(map);
    }

    /**
     * Convert a Java method name to its gRPC PascalCase equivalent.
     * e.g. {@code sayHello} → {@code SayHello}
     */
    static String toGrpcMethodName(String javaMethodName) {
        if (javaMethodName == null || javaMethodName.isEmpty()) {
            return javaMethodName;
        }
        return Character.toUpperCase(javaMethodName.charAt(0)) + javaMethodName.substring(1);
    }

    /**
     * Resolve the type argument of {@code StreamSource<T>} from the method's
     * generic return type.
     */
    private static Class<?> resolveStreamSourceTypeArgument(Method method) {
        Type genericReturn = method.getGenericReturnType();
        if (genericReturn instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> clazz
                    && Message.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        }
        throw new IllegalArgumentException(
                "Cannot resolve StreamSource type argument for streaming method: "
                        + method.getDeclaringClass().getName() + "." + method.getName()
                        + ". The type argument must be a concrete protobuf Message class.");
    }
    
    /**
     * Resolve the type argument of a {@code StreamObserver<T>} parameter.
     * Used for bidirectional streaming methods where the request stream
     * carries protobuf messages.
     */
    private static Class<?> resolveStreamObserverParamType(Method method, Parameter param) {
        Type genericType = param.getParameterizedType();
        if (genericType instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> clazz
                    && Message.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        }
        throw new IllegalArgumentException(
                "Cannot resolve StreamObserver type argument for bidirectional streaming parameter: "
                        + method.getDeclaringClass().getName() + "." + method.getName()
                        + ". The type argument must be a concrete protobuf Message class.");
    }

    /**
     * Collect all unique protobuf {@link Descriptors.FileDescriptor}s from the
     * request and response message types, including transitive dependencies.
     *
     * @return the collected file descriptors
     */
    public Set<Descriptors.FileDescriptor> getFileDescriptors() {
        Set<Descriptors.FileDescriptor> result = new HashSet<>();
        for (MethodInfo info : methodInfoMap.values()) {
            collectFromFileDescriptor(info.requestClass(), result);
            collectFromFileDescriptor(info.responseClass(), result);
        }
        return result;
    }

    private static void collectFromFileDescriptor(Class<? extends Message> messageClass,
                                                   Set<Descriptors.FileDescriptor> collected) {
        try {
            Message defaultInstance = (Message) messageClass.getMethod("getDefaultInstance").invoke(null);
            Descriptors.FileDescriptor fd = ((Message) defaultInstance).getDescriptorForType().getFile();
            collectFileDescriptorRecursive(fd, collected);
        } catch (Exception e) {
            // Skip types that don't expose getDefaultInstance
        }
    }

    private static void collectFileDescriptorRecursive(Descriptors.FileDescriptor fd,
                                                        Set<Descriptors.FileDescriptor> collected) {
        if (!collected.add(fd)) {
            return;
        }
        for (Descriptors.FileDescriptor dep : fd.getDependencies()) {
            collectFileDescriptorRecursive(dep, collected);
        }
    }

    private static Class<? extends Message> asMessageClass(Class<?> clazz) {
        //noinspection unchecked
        return (Class<? extends Message>) clazz;
    }

    /**
     * Reflectively invoke the static {@code parser()} method on a protobuf
     * generated class to obtain its {@link Parser}.
     */
    private static Parser<? extends Message> resolveParser(Class<?> messageClass) {
        try {
            Method parserMethod = messageClass.getMethod("parser");
            //noinspection unchecked
            return (Parser<? extends Message>) parserMethod.invoke(null);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to obtain protobuf parser for: " + messageClass.getName(), e);
        }
    }
}
