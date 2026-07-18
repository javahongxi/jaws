package org.hongxi.jaws.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Utility for generic invocation parameter type conversion.
 * <p>
 * Converts {@code Map<String, Object>} representations to actual POJO types
 * and handles primitive/wrapper type conversions.
 * <p>
 * Created by shenhongxi on 2026/7/18.
 */
public class GenericUtils {

    private static final Logger log = LoggerFactory.getLogger(GenericUtils.class);

    private static final Map<String, Class<?>> PRIMITIVE_TYPES = new HashMap<>();

    static {
        PRIMITIVE_TYPES.put("boolean", boolean.class);
        PRIMITIVE_TYPES.put("byte", byte.class);
        PRIMITIVE_TYPES.put("char", char.class);
        PRIMITIVE_TYPES.put("short", short.class);
        PRIMITIVE_TYPES.put("int", int.class);
        PRIMITIVE_TYPES.put("long", long.class);
        PRIMITIVE_TYPES.put("float", float.class);
        PRIMITIVE_TYPES.put("double", double.class);
        PRIMITIVE_TYPES.put("void", void.class);
    }

    /**
     * Convert a parameter type name to its Class.
     */
    public static Class<?> forName(String className) throws ClassNotFoundException {
        Class<?> primitive = PRIMITIVE_TYPES.get(className);
        if (primitive != null) {
            return primitive;
        }
        // Handle array types like "java.lang.String[]"
        if (className.endsWith("[]")) {
            String componentType = className.substring(0, className.length() - 2);
            Class<?> componentClass = forName(componentType);
            return Array.newInstance(componentClass, 0).getClass();
        }
        return Class.forName(className);
    }

    /**
     * Build a parameter description string from parameter type names,
     * matching the format used by {@link ReflectUtils#getMethodDesc(String, Class[])}.
     */
    public static String buildParameterDesc(String[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(parameterTypes[i]);
        }
        return sb.toString();
    }

    /**
     * Convert an argument to the expected parameter type.
     * <ul>
     *   <li>If arg is already the expected type, return as-is</li>
     *   <li>If arg is a Map and expected type is a POJO, convert via reflection</li>
     *   <li>If expected type is a primitive/wrapper, do numeric conversion</li>
     *   <li>If expected type is String, convert via toString()</li>
     * </ul>
     */
    public static Object convertArgument(Object arg, Class<?> expectedType) {
        if (arg == null) {
            return null;
        }

        // Already the right type
        if (expectedType.isInstance(arg)) {
            return arg;
        }

        // Map -> POJO conversion
        if (arg instanceof Map && !Map.class.isAssignableFrom(expectedType)) {
            return convertMapToPojo((Map<String, Object>) arg, expectedType);
        }

        // Primitive/wrapper conversions
        if (expectedType == String.class) {
            return arg.toString();
        }
        if (expectedType == int.class || expectedType == Integer.class) {
            return ((Number) arg).intValue();
        }
        if (expectedType == long.class || expectedType == Long.class) {
            return ((Number) arg).longValue();
        }
        if (expectedType == double.class || expectedType == Double.class) {
            return ((Number) arg).doubleValue();
        }
        if (expectedType == float.class || expectedType == Float.class) {
            return ((Number) arg).floatValue();
        }
        if (expectedType == boolean.class || expectedType == Boolean.class) {
            return Boolean.valueOf(arg.toString());
        }
        if (expectedType == byte.class || expectedType == Byte.class) {
            return ((Number) arg).byteValue();
        }
        if (expectedType == short.class || expectedType == Short.class) {
            return ((Number) arg).shortValue();
        }
        if (expectedType == char.class || expectedType == Character.class) {
            if (arg instanceof Character) {
                return arg;
            }
            String s = arg.toString();
            return s.isEmpty() ? '\0' : s.charAt(0);
        }

        // List/Collection conversion
        if (arg instanceof Collection && expectedType.isArray()) {
            Collection<?> coll = (Collection<?>) arg;
            Class<?> componentType = expectedType.getComponentType();
            Object array = Array.newInstance(componentType, coll.size());
            int i = 0;
            for (Object item : coll) {
                Array.set(array, i++, convertArgument(item, componentType));
            }
            return array;
        }

        // Fallback: return as-is and let the method invocation handle any type mismatch
        return arg;
    }

    /**
     * Convert a Map to a POJO using reflection.
     */
    @SuppressWarnings("unchecked")
    public static <T> T convertMapToPojo(Map<String, Object> map, Class<T> clazz) {
        try {
            T instance;
            // Try no-arg constructor first
            try {
                Constructor<T> constructor = clazz.getDeclaredConstructor();
                constructor.setAccessible(true);
                instance = constructor.newInstance();
            } catch (NoSuchMethodException e) {
                // If no no-arg constructor, try to use Unsafe or return map as-is
                log.warn("Cannot create instance of {} via no-arg constructor, returning map as-is", clazz.getName());
                return (T) map;
            }

            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String fieldName = entry.getKey();
                Object value = entry.getValue();
                try {
                    Method setter = findSetter(clazz, fieldName);
                    if (setter != null) {
                        Class<?> paramType = setter.getParameterTypes()[0];
                        Object convertedValue = convertArgument(value, paramType);
                        setter.invoke(instance, convertedValue);
                    } else {
                        // Try direct field access
                        java.lang.reflect.Field field = findField(clazz, fieldName);
                        if (field != null) {
                            field.setAccessible(true);
                            Object convertedValue = convertArgument(value, field.getType());
                            field.set(instance, convertedValue);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to set field '{}' on {}: {}", fieldName, clazz.getName(), e.getMessage());
                }
            }
            return instance;
        } catch (Exception e) {
            log.error("Failed to convert Map to POJO: class={}", clazz.getName(), e);
            throw new RuntimeException("Failed to convert Map to POJO: " + clazz.getName(), e);
        }
    }

    private static Method findSetter(Class<?> clazz, String fieldName) {
        String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Convert a POJO result to a Map for generic invocation response.
     */
    public static Object convertResult(Object result) {
        if (result == null) {
            return null;
        }
        Class<?> clazz = result.getClass();
        // Primitives, wrappers, String, and other simple types pass through
        if (clazz.isPrimitive() || isWrapperType(clazz) || result instanceof String
                || result instanceof Number || result instanceof Boolean
                || result instanceof Date || result instanceof Enum) {
            return result;
        }
        // Collections and arrays pass through
        if (result instanceof Collection || result.getClass().isArray()) {
            return result;
        }
        // Convert POJO to Map
        return pojoToMap(result);
    }

    private static Map<String, Object> pojoToMap(Object obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        Class<?> clazz = obj.getClass();
        for (Method method : clazz.getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() == 0 && !name.equals("getClass")) {
                String fieldName;
                if (name.startsWith("get") && name.length() > 3) {
                    fieldName = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                } else if (name.startsWith("is") && name.length() > 2
                        && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                    fieldName = Character.toLowerCase(name.charAt(2)) + name.substring(3);
                } else {
                    continue;
                }
                try {
                    Object value = method.invoke(obj);
                    map.put(fieldName, value);
                } catch (Exception e) {
                    log.debug("Failed to read field '{}' from {}: {}", fieldName, clazz.getName(), e.getMessage());
                }
            }
        }
        return map;
    }

    private static boolean isWrapperType(Class<?> clazz) {
        return clazz == Boolean.class || clazz == Byte.class || clazz == Character.class
                || clazz == Short.class || clazz == Integer.class || clazz == Long.class
                || clazz == Float.class || clazz == Double.class;
    }
}
