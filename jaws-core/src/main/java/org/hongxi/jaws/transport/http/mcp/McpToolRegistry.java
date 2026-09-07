package org.hongxi.jaws.transport.http.mcp;

import com.alibaba.fastjson2.JSON;
import org.hongxi.jaws.rpc.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of MCP tools exposed through the Streamable HTTP transport.
 * <p>
 * Each tool has a name, description, JSON Schema input specification, and
 * an execution function. Tools are the MCP mechanism for AI agents to invoke
 * server-side capabilities — analogous to Jaws RPC service methods.
 * <p>
 * The {@link #register(Provider)} method automatically registers each public
 * interface method of a {@link Provider} as an MCP tool.
 *
 * @author shenhongxi
 */
public class McpToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    /**
     * An MCP tool that can be listed and called by MCP clients.
     *
     * @param name        unique tool name (e.g. "hello")
     * @param description human-readable description for the AI model
     * @param inputSchema JSON Schema object describing the tool's parameters
     * @param executor    function that executes the tool with the given arguments
     */
    public record Tool(String name, String description, Map<String, Object> inputSchema, Executor executor) {
    }

    /**
     * Functional interface for tool execution.
     */
    @FunctionalInterface
    public interface Executor {
        /**
         * Execute the tool.
         *
         * @param arguments the tool arguments as a JSON-like map
         * @return the tool result (will be serialized to JSON)
         */
        Object execute(Map<String, Object> arguments) throws Exception;
    }

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * Register a tool.
     *
     * @param tool the tool to register
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Look up a tool by name.
     *
     * @param name the tool name
     * @return the tool, or null if not found
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * @return all registered tools as an unmodifiable snapshot
     */
    public Map<String, Tool> getAll() {
        return Map.copyOf(tools);
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * Register each public interface method of a {@link Provider} as an MCP tool.
     * <p>
     * Overloaded methods (same name, different parameters) are skipped with a warning
     * because MCP tools are identified by name only.
     *
     * @param provider the Jaws provider whose interface methods become MCP tools
     */
    public void register(Provider<?> provider) {
        Class<?> interfaceClass = provider.getInterface();
        Object impl = provider.getImpl();
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

            register(new Tool(toolName, description, inputSchema, args -> {
                Object[] methodArgs = resolveArguments(method, args);
                Object result = method.invoke(impl, methodArgs);

                // Handle async return values
                if (result instanceof java.util.concurrent.CompletableFuture<?> future) {
                    result = future.join();
                }

                return result;
            }));

            log.debug("Registered MCP tool: {} ({})", toolName, description);
        }
    }

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

    private static Object[] resolveArguments(Method method, Map<String, Object> args) {
        Parameter[] params = method.getParameters();
        Object[] resolved = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            Object value = args != null ? args.get(params[i].getName()) : null;
            resolved[i] = convertValue(value, params[i].getType());
        }
        return resolved;
    }

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
