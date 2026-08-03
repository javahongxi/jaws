package org.hongxi.jaws.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/**
 * Generates JSON Schema (draft 2020-12) from Java types for service method input schemas.
 * <p>
 * Supports primitives, wrappers, String, Date/Time types, BigDecimal/BigInteger,
 * collections, arrays, maps, enums, and complex POJOs (with recursive field expansion).
 * <p>
 * Shared across protocol bridges (MCP, REST, etc.).
 *
 * @author shenhongxi
 */
public class JsonSchemaGenerator {

    private static final int MAX_DEPTH = 5;

    private static final Map<Class<?>, Map<String, Object>> PRIMITIVE_SCHEMAS = new HashMap<>();

    private static final Set<Class<?>> SIMPLE_TYPES = new HashSet<>();

    static {
        // boolean
        PRIMITIVE_SCHEMAS.put(boolean.class, Map.of("type", "boolean"));
        PRIMITIVE_SCHEMAS.put(Boolean.class, Map.of("type", "boolean"));
        // integer types
        Map<String, Object> intSchema = Map.of("type", "integer");
        PRIMITIVE_SCHEMAS.put(int.class, intSchema);
        PRIMITIVE_SCHEMAS.put(Integer.class, intSchema);
        PRIMITIVE_SCHEMAS.put(long.class, intSchema);
        PRIMITIVE_SCHEMAS.put(Long.class, intSchema);
        PRIMITIVE_SCHEMAS.put(short.class, intSchema);
        PRIMITIVE_SCHEMAS.put(Short.class, intSchema);
        PRIMITIVE_SCHEMAS.put(byte.class, intSchema);
        PRIMITIVE_SCHEMAS.put(Byte.class, intSchema);
        // number types
        Map<String, Object> numSchema = Map.of("type", "number");
        PRIMITIVE_SCHEMAS.put(float.class, numSchema);
        PRIMITIVE_SCHEMAS.put(Float.class, numSchema);
        PRIMITIVE_SCHEMAS.put(double.class, numSchema);
        PRIMITIVE_SCHEMAS.put(Double.class, numSchema);
        PRIMITIVE_SCHEMAS.put(BigDecimal.class, numSchema);
        PRIMITIVE_SCHEMAS.put(BigInteger.class, numSchema);
        // string
        Map<String, Object> strSchema = Map.of("type", "string");
        PRIMITIVE_SCHEMAS.put(String.class, strSchema);
        PRIMITIVE_SCHEMAS.put(char.class, strSchema);
        PRIMITIVE_SCHEMAS.put(Character.class, strSchema);

        // Date/Time types → string
        PRIMITIVE_SCHEMAS.put(Date.class, strSchema);
        PRIMITIVE_SCHEMAS.put(LocalDate.class, strSchema);
        PRIMITIVE_SCHEMAS.put(LocalDateTime.class, strSchema);
        PRIMITIVE_SCHEMAS.put(LocalTime.class, strSchema);
        PRIMITIVE_SCHEMAS.put(OffsetDateTime.class, strSchema);
        PRIMITIVE_SCHEMAS.put(OffsetTime.class, strSchema);
        PRIMITIVE_SCHEMAS.put(ZonedDateTime.class, strSchema);
        PRIMITIVE_SCHEMAS.put(Instant.class, strSchema);

        SIMPLE_TYPES.addAll(PRIMITIVE_SCHEMAS.keySet());
    }

    /**
     * Generate a JSON Schema "object" for a method's parameters.
     * The resulting schema has type=object with properties for each parameter.
     *
     * @param parameters the method parameters
     * @return JSON Schema map
     */
    public static Map<String, Object> generateMethodSchema(Parameter[] parameters) {
        return generateMethodSchema(parameters, 0);
    }

    private static Map<String, Object> generateMethodSchema(Parameter[] parameters, int depth) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        if (parameters.length == 0) {
            schema.put("additionalProperties", false);
            return schema;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : parameters) {
            String paramName = param.isNamePresent() ? param.getName() : "arg" + param.getParameterizedType().hashCode();
            Map<String, Object> paramSchema = generateTypeSchema(param.getParameterizedType(), depth);
            properties.put(paramName, paramSchema);
            // non-primitive types are considered required
            if (!param.getType().isPrimitive()) {
                required.add(paramName);
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Generate a JSON Schema for a single Java type.
     */
    public static Map<String, Object> generateTypeSchema(Type type) {
        return generateTypeSchema(type, 0);
    }

    private static Map<String, Object> generateTypeSchema(Type type, int depth) {
        if (depth > MAX_DEPTH) {
            return Map.of("type", "object", "description", "Complex object (max depth exceeded)");
        }

        Class<?> rawClass = getRawClass(type);
        if (rawClass == null) {
            return Map.of("type", "string");
        }

        // Check primitive/simple types
        Map<String, Object> primitiveSchema = PRIMITIVE_SCHEMAS.get(rawClass);
        if (primitiveSchema != null) {
            return primitiveSchema;
        }

        // void
        if (rawClass == void.class || rawClass == Void.class) {
            return Map.of();
        }

        // Enum
        if (rawClass.isEnum()) {
            return generateEnumSchema(rawClass);
        }

        // Array
        if (rawClass.isArray()) {
            Map<String, Object> itemSchema = generateTypeSchema(rawClass.getComponentType(), depth + 1);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            schema.put("items", itemSchema);
            return schema;
        }

        // Collection (List, Set, Collection)
        if (Collection.class.isAssignableFrom(rawClass)) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            Type elementType = getGenericTypeArgument(type, 0);
            if (elementType != null) {
                schema.put("items", generateTypeSchema(elementType, depth + 1));
            } else {
                schema.put("items", Map.of("type", "object"));
            }
            return schema;
        }

        // Map
        if (Map.class.isAssignableFrom(rawClass)) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Type valueType = getGenericTypeArgument(type, 1);
            if (valueType != null) {
                schema.put("additionalProperties", generateTypeSchema(valueType, depth + 1));
            }
            return schema;
        }

        // Complex object (POJO)
        return generateObjectSchema(rawClass, depth);
    }

    private static Map<String, Object> generateEnumSchema(Class<?> enumClass) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        Object[] constants = enumClass.getEnumConstants();
        if (constants != null) {
            List<String> enumValues = new ArrayList<>();
            for (Object c : constants) {
                enumValues.add(((Enum<?>) c).name());
            }
            schema.put("enum", enumValues);
        }
        return schema;
    }

    private static Map<String, Object> generateObjectSchema(Class<?> clazz, int depth) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        // Collect fields from the class hierarchy
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                Map<String, Object> fieldSchema = generateTypeSchema(field.getGenericType(), depth + 1);
                properties.put(field.getName(), fieldSchema);
                if (!field.getType().isPrimitive()) {
                    required.add(field.getName());
                }
            }
            current = current.getSuperclass();
        }

        if (!properties.isEmpty()) {
            schema.put("properties", properties);
        }
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Class<?> getRawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        return null;
    }

    private static Type getGenericTypeArgument(Type type, int index) {
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (index < args.length) {
                return args[index];
            }
        }
        return null;
    }
}
