package org.hongxi.jaws.bridge;

import com.alibaba.fastjson2.JSON;

import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * Utility for converting external arguments (from MCP, REST, etc.) to Java method parameters.
 * <p>
 * Handles primitive types, wrappers, String, and complex POJOs (via fastjson2 conversion).
 * This class is shared across protocol bridges to avoid code duplication.
 *
 * @author shenhongxi
 */
public final class ArgumentConverter {

    private ArgumentConverter() {
    }

    /**
     * Convert a map of arguments to an Object array matching the method parameters.
     *
     * @param parameters the method parameters
     * @param arguments  the argument map (key: parameter name or "argN", value: argument value)
     * @return array of converted argument values
     */
    public static Object[] convertArguments(Parameter[] parameters, Map<String, Object> arguments) {
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
     *
     * @param value      the raw argument value
     * @param targetType the expected Java type
     * @return converted value
     */
    public static Object convertArgument(Object value, Class<?> targetType) {
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

    /**
     * Get the default value for a primitive type.
     *
     * @param type the primitive type
     * @return default value (0, false, etc.) or null for non-primitives
     */
    public static Object getDefaultForType(Class<?> type) {
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
}
