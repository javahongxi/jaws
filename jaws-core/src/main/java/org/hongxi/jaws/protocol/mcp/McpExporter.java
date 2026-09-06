package org.hongxi.jaws.protocol.mcp;

import com.alibaba.fastjson2.JSON;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.AbstractExporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.mcp.McpServer;
import org.hongxi.jaws.transport.mcp.McpToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP protocol exporter that bridges Jaws service methods to MCP tools.
 * <p>
 * When a service is exported via the {@code mcp} protocol, this exporter:
 * <ol>
 *   <li>Creates (or reuses) an {@link McpServer} on the configured port</li>
 *   <li>Auto-registers each public interface method as an MCP tool, generating
 *       a JSON Schema from the method's parameter types and wiring the executor
 *       to invoke the actual service implementation via reflection</li>
 *   <li>Delegates lifecycle (open/close/drain) to the underlying Netty server</li>
 * </ol>
 * <p>
 * This enables any Jaws service to be directly callable from MCP clients
 * (Cursor, Claude Desktop, MCP Inspector) without manual tool registration.
 *
 * @see McpProtocol
 * @see McpServer
 */
public class McpExporter<T> extends AbstractExporter<T> {

    private static final Logger log = LoggerFactory.getLogger(McpExporter.class);

    /** Shared MCP servers keyed by host:port — multiple services share one server. */
    private static final ConcurrentMap<String, McpServer> SERVER_MAP = new ConcurrentHashMap<>();

    private final McpServer server;

    public McpExporter(Provider<T> provider, URL url) {
        super(provider, url);
        server = SERVER_MAP.computeIfAbsent(url.getHostPort(), k -> new McpServer(url));
        registerTools(provider, server.getToolRegistry());
    }

    // ---- Lifecycle ----

    @Override
    protected boolean doInit() {
        return server.open();
    }

    @Override
    public boolean isAvailable() {
        return server.isAvailable();
    }

    @Override
    public void destroy() {
        SERVER_MAP.remove(url.getHostPort());
        server.close();
        log.info("McpExporter destroy: url={}", url);
    }

    @Override
    public void stopAccept() {
        server.stopAccept();
    }

    @Override
    public void drainInflightRequests(long timeout) {
        server.drainInflightRequests(timeout);
    }

    // ---- Tool auto-registration ----

    /**
     * Register each public interface method as an MCP tool.
     * Overloaded methods (same name, different parameters) are skipped with a warning
     * because MCP tools are identified by name only.
     */
    private void registerTools(Provider<T> provider, McpToolRegistry registry) {
        Class<T> interfaceClass = provider.getInterface();
        T impl = provider.getImpl();
        Set<String> seenNames = new HashSet<>();

        for (Method method : interfaceClass.getMethods()) {
            if (method.isDefault() || method.isSynthetic()) {
                continue;
            }
            if (!seenNames.add(method.getName())) {
                log.warn("Skipping overloaded method as MCP tool (name collision): {}.{}",
                        interfaceClass.getSimpleName(), method.getName());
                continue;
            }

            String toolName = method.getName();
            String description = interfaceClass.getSimpleName() + "." + method.getName();
            Map<String, Object> inputSchema = buildInputSchema(method);

            registry.register(new McpToolRegistry.Tool(toolName, description, inputSchema, args -> {
                Object[] methodArgs = resolveArguments(method, args);
                Object result = method.invoke(impl, methodArgs);

                // Handle async return values
                if (result instanceof CompletableFuture<?> future) {
                    long timeoutMs = url.getMethodParameter(
                            method.getName(), "",
                            UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                            UrlParam.Transport.REQUEST_TIMEOUT.intValue());
                    if (timeoutMs > 0) {
                        result = future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join();
                    } else {
                        result = future.join();
                    }
                }

                return result;
            }));

            log.debug("Registered MCP tool: {} ({})", toolName, description);
        }
    }

    /**
     * Build a JSON Schema object for the method's parameters.
     * <p>
     * Example output for {@code void hello(String name, int count)}:
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "name": {"type": "string", "description": "String"},
     *     "count": {"type": "integer", "description": "int"}
     *   },
     *   "required": ["name", "count"]
     * }
     * </pre>
     */
    private static Map<String, Object> buildInputSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Parameter[] params = method.getParameters();

        for (Parameter param : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", jsonSchemaType(param.getType()));
            prop.put("description", param.getType().getSimpleName());
            properties.put(param.getName(), prop);
            required.add(param.getName());
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * Map a Java type to its JSON Schema type string.
     */
    private static String jsonSchemaType(Class<?> type) {
        if (type == String.class || type == CharSequence.class) {
            return "string";
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(type)) {
            if (type == float.class || type == Float.class
                    || type == double.class || type == Double.class) {
                return "number";
            }
            return "integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        return "object";
    }

    /**
     * Convert MCP tool arguments (a flat {@code Map<String, Object>}) to the
     * method's parameter array, using the same type-coercion strategy as the
     * HTTP transport's {@code convertValue}.
     */
    private static Object[] resolveArguments(Method method, Map<String, Object> args) {
        Parameter[] params = method.getParameters();
        Object[] resolved = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Object value = args != null ? args.get(params[i].getName()) : null;
            resolved[i] = convertValue(value, params[i].getType());
        }
        return resolved;
    }

    /**
     * Convert a single value to the target type.
     * Handles primitives, wrappers, strings, and complex objects (via JSON round-trip).
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return defaultForPrimitive(targetType);
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            if (targetType == int.class || targetType == Integer.class) return number.intValue();
            if (targetType == long.class || targetType == Long.class) return number.longValue();
            if (targetType == double.class || targetType == Double.class) return number.doubleValue();
            if (targetType == float.class || targetType == Float.class) return number.floatValue();
            if (targetType == short.class || targetType == Short.class) return number.shortValue();
            if (targetType == byte.class || targetType == Byte.class) return number.byteValue();
            if (targetType == boolean.class || targetType == Boolean.class) return number.intValue() != 0;
        }
        if (value instanceof Boolean b) {
            if (targetType == boolean.class || targetType == Boolean.class) return b;
        }
        if (targetType == String.class || targetType == CharSequence.class) {
            return value.toString();
        }
        // Complex object: JSON round-trip
        return JSON.parseObject(JSON.toJSONString(value), targetType);
    }

    private static Object defaultForPrimitive(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == boolean.class) return false;
        return null;
    }
}
