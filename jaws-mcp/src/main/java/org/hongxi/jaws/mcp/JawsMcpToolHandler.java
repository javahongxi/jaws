package org.hongxi.jaws.mcp;

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.spec.McpSchema;
import org.hongxi.jaws.common.util.ReflectUtils;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.DefaultResponse;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.rpc.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * Handles MCP Tool calls by bridging them to Jaws {@link Provider} invocations.
 * <p>
 * Converts {@link McpSchema.CallToolRequest} arguments into a Jaws
 * {@link org.hongxi.jaws.rpc.Request}, invokes the provider, and converts
 * the {@link Response} back to a {@link McpSchema.CallToolResult}.
 *
 * @author shenhongxi
 */
public class JawsMcpToolHandler {

    private static final Logger log = LoggerFactory.getLogger(JawsMcpToolHandler.class);

    /**
     * Handle a tool call from MCP client.
     *
     * @param spec the tool specification containing method and provider info
     * @param request the MCP call request with arguments
     * @return the MCP call result with text content
     */
    public static McpSchema.CallToolResult handleToolCall(JawsMcpToolSpec spec, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> arguments = request.arguments();

            // Build Jaws Request
            DefaultRequest jawsRequest = new DefaultRequest();
            jawsRequest.setInterfaceName(spec.getInterfaceName());
            jawsRequest.setMethodName(spec.getMethodName());
            jawsRequest.setParametersDesc(buildParametersDesc(spec.getParameterTypes()));

            // Convert arguments from Map to Object[]
            Object[] args = convertArguments(spec.getParameters(), arguments);
            jawsRequest.setArguments(args);

            // Invoke provider
            Provider<?> provider = spec.getProvider();
            Response response = provider.call(jawsRequest);

            // Convert response to CallToolResult
            if (response instanceof DefaultResponse dr) {
                if (dr.getException() != null) {
                    return createErrorResult(dr.getException());
                }
                Object value = dr.getValue();
                return createSuccessResult(value);
            }

            if (response.getException() != null) {
                return createErrorResult(response.getException());
            }
            return createSuccessResult(null);

        } catch (Exception e) {
            log.error("[JawsMcp] Tool call failed: {}", spec.getToolName(), e);
            return createErrorResult(e);
        }
    }

    private static String buildParametersDesc(String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return ReflectUtils.EMPTY_PARAM;
        }
        return String.join(ReflectUtils.PARAM_CLASS_SPLIT, parameterTypes);
    }

    /**
     * Convert MCP arguments map to Jaws method argument array.
     */
    private static Object[] convertArguments(Parameter[] parameters, Map<String, Object> arguments) {
        if (parameters.length == 0) {
            return new Object[0];
        }

        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            String paramName = param.isNamePresent() ? param.getName() : null;

            Object value = null;
            if (arguments != null) {
                if (paramName != null && arguments.containsKey(paramName)) {
                    value = arguments.get(paramName);
                } else if (arguments.containsKey("arg" + i)) {
                    value = arguments.get("arg" + i);
                } else if (arguments.size() == 1) {
                    // Single argument: use the only value
                    value = arguments.values().iterator().next();
                }
            }

            args[i] = convertArgument(value, param.getType());
        }
        return args;
    }

    /**
     * Convert a single argument value to the expected Java type.
     * Uses fastjson2 for complex type conversions.
     */
    private static Object convertArgument(Object value, Class<?> targetType) {
        if (value == null) {
            return getDefaultForType(targetType);
        }

        // Already the right type
        if (targetType.isInstance(value)) {
            return value;
        }

        // Primitive conversions
        if (targetType == String.class) {
            return value.toString();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return ((Number) value).intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return ((Number) value).longValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return ((Number) value).doubleValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return ((Number) value).floatValue();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value;
        }
        if (targetType == short.class || targetType == Short.class) {
            return ((Number) value).shortValue();
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return ((Number) value).byteValue();
        }

        // Complex type: use fastjson2 for conversion
        String json = JSON.toJSONString(value);
        return JSON.parseObject(json, targetType);
    }

    private static Object getDefaultForType(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return '\0';
        }
        return null;
    }

    private static McpSchema.CallToolResult createSuccessResult(Object value) {
        String text;
        if (value == null) {
            text = "null";
        } else if (value instanceof String s) {
            text = s;
        } else {
            text = JSON.toJSONString(value);
        }

        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .isError(false)
                .build();
    }

    private static McpSchema.CallToolResult createErrorResult(Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getName();
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + message)
                .isError(true)
                .build();
    }
}
