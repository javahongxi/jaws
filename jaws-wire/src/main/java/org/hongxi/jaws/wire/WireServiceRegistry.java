package org.hongxi.jaws.wire;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of gRPC method handlers, mapping {@code /{serviceName}/{methodName}}
 * paths to their {@link WireMethodHandler} implementations.
 * <p>
 * Thread-safe: handlers can be registered and resolved concurrently.
 *
 * @author shenhongxi
 */
public class WireServiceRegistry {

    private final ConcurrentMap<String, WireMethodHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Register a method handler for the given service and method.
     *
     * @param serviceName  the fully-qualified protobuf service name (e.g. {@code demo.DemoService})
     * @param methodName   the RPC method name (e.g. {@code SayHello})
     * @param handler      the method handler
     */
    public void register(String serviceName, String methodName, WireMethodHandler handler) {
        String path = "/" + serviceName + "/" + methodName;
        handlers.put(path, handler);
    }

    /**
     * Resolve the handler for the given gRPC path.
     *
     * @param path the request path (e.g. {@code /demo.DemoService/SayHello})
     * @return the handler, or {@code null} if no handler is registered for the path
     */
    public WireMethodHandler resolve(String path) {
        return handlers.get(path);
    }

    /**
     * @return an unmodifiable view of all registered paths
     */
    public Set<String> getPaths() {
        return Collections.unmodifiableSet(handlers.keySet());
    }
}
