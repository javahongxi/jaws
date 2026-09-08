package org.hongxi.jaws.wire;

import com.google.protobuf.Descriptors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of gRPC method handlers, mapping {@code /{serviceName}/{methodName}}
 * paths to their {@link WireMethodHandler} implementations.
 * <p>
 * Thread-safe: handlers can be registered and resolved concurrently.
 *
 * @author shenhongxi
 */
public class WireHandlerRegistry {

    private final ConcurrentMap<String, WireMethodHandler> handlers = new ConcurrentHashMap<>();

    /** Ordered list of server interceptors applied to all calls in Direct API mode. */
    private final List<WireServerInterceptor> interceptors = new CopyOnWriteArrayList<>();

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
     * Add a server interceptor applied to all calls in Direct API mode.
     * Interceptors execute in registration order (first added = outermost).
     *
     * @param interceptor the interceptor to add
     */
    public void addInterceptor(WireServerInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    /**
     * @return the registered interceptors (unmodifiable view)
     */
    public List<WireServerInterceptor> getInterceptors() {
        return List.copyOf(interceptors);
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
     * @return the distinct service names extracted from all registered paths,
     *         sorted alphabetically (e.g. {@code [demo.DemoService, grpc.health.v1.Health]})
     */
    public Set<String> listServiceNames() {
        Set<String> names = new TreeSet<>();
        for (String path : handlers.keySet()) {
            // path format: /{serviceName}/{methodName}
            String trimmed = path.startsWith("/") ? path.substring(1) : path;
            int slashIdx = trimmed.indexOf('/');
            if (slashIdx > 0) {
                names.add(trimmed.substring(0, slashIdx));
            }
        }
        return names;
    }

    /**
     * Collect all unique protobuf {@link Descriptors.FileDescriptor}s from the
     * request and response types of all registered handlers, including
     * transitive dependencies.
     *
     * @return the collected file descriptors
     */
    public Set<Descriptors.FileDescriptor> collectFileDescriptors() {
        Set<Descriptors.FileDescriptor> result = new LinkedHashSet<>();
        for (WireMethodHandler handler : handlers.values()) {
            collectFromParser(handler.getRequestParser(), result);
        }
        return result;
    }

    private static void collectFromParser(com.google.protobuf.Parser<?> parser,
                                           Set<Descriptors.FileDescriptor> collected) {
        try {
            // Parsing empty bytes yields the default instance, from which
            // we can extract the FileDescriptor
            com.google.protobuf.MessageLite defaultInstance =
                    (com.google.protobuf.MessageLite) parser.parseFrom(new byte[0]);
            if (defaultInstance instanceof com.google.protobuf.Message msg) {
                Descriptors.FileDescriptor fd = msg.getDescriptorForType().getFile();
                collectFdRecursive(fd, collected);
            }
        } catch (Exception e) {
            // Skip parsers that fail to produce a default instance
        }
    }

    private static void collectFdRecursive(Descriptors.FileDescriptor fd,
                                            Set<Descriptors.FileDescriptor> collected) {
        if (!collected.add(fd)) {
            return;
        }
        for (Descriptors.FileDescriptor dep : fd.getDependencies()) {
            collectFdRecursive(dep, collected);
        }
    }
}
