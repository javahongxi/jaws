package org.hongxi.jaws.common.util;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reflection utility
 */
public class ReflectUtils {
    public static final String PARAM_CLASS_SPLIT = ",";
    public static final String EMPTY_PARAM = "void";
    private static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];

    private static final ConcurrentMap<String, Class<?>> name2ClassCache = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, String> class2NameCache = new ConcurrentHashMap<>();

    private static final String[] PRIMITIVE_NAMES = new String[]{
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void"};

    private static final Class<?>[] PRIMITIVE_CLASSES = new Class<?>[]{
            boolean.class, byte.class, char.class, double.class, float.class,
            int.class, long.class, short.class, Void.TYPE};

    private static final int PRIMITIVE_CLASS_NAME_MAX_LENGTH = 7;

    /**
     * Get method parameter types as comma-separated class name string.
     * Returns "void" if the method has no parameters.
     */
    public static String getMethodParamDesc(Method method) {
        if (method.getParameterTypes().length == 0) {
            return EMPTY_PARAM;
        }

        StringBuilder builder = new StringBuilder();

        for (Class<?> paramType : method.getParameterTypes()) {
            builder.append(getName(paramType)).append(PARAM_CLASS_SPLIT);
        }

        return builder.substring(0, builder.length() - 1);
    }

    /**
     * Get method descriptor: method_name + "(" + paramDesc + ")"
     */
    public static String getMethodDesc(Method method) {
        String methodParamDesc = getMethodParamDesc(method);
        return getMethodDesc(method.getName(), methodParamDesc);
    }

    /**
     * Get method descriptor: method_name + "(" + paramDesc + ")"
     */
    public static String getMethodDesc(String methodName, String paramDesc) {
        if (paramDesc == null) {
            return methodName + "()";
        } else {
            return methodName + "(" + paramDesc + ")";
        }
    }

    public static Class<?>[] forNames(String classList) throws ClassNotFoundException {
        if (classList == null || classList.isEmpty() || EMPTY_PARAM.equals(classList)) {
            return EMPTY_CLASS_ARRAY;
        }

        String[] classNames = classList.split(PARAM_CLASS_SPLIT);
        Class<?>[] classTypes = new Class<?>[classNames.length];
        for (int i = 0; i < classNames.length; i++) {
            String className = classNames[i];

            classTypes[i] = forName(className);
        }

        return classTypes;
    }

    public static Class<?> forName(String className) throws ClassNotFoundException {
        if (null == className || className.isEmpty()) {
            return null;
        }

        Class<?> clazz = name2ClassCache.get(className);

        if (clazz != null) {
            return clazz;
        }

        clazz = forNameWithoutCache(className);

        // Memory consumption should be minimal unless an excessive number of classes are created
        name2ClassCache.putIfAbsent(className, clazz);

        return clazz;
    }

    private static Class<?> forNameWithoutCache(String className) throws ClassNotFoundException {
        if (!className.endsWith("[]")) {
            Class<?> clazz = getPrimitiveClass(className);
            if (clazz != null) {
                return clazz;
            }
            return Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        }

        int dimensionSize = 0;

        while (className.endsWith("[]")) {
            dimensionSize++;
            className = className.substring(0, className.length() - 2);
        }

        int[] dimensions = new int[dimensionSize];

        Class<?> clazz = getPrimitiveClass(className);

        if (clazz == null) {
            clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        }

        return Array.newInstance(clazz, dimensions).getClass();
    }

    /**
     * Supports 1D arrays, 2D arrays, etc.
     */
    public static String getName(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }

        String className = class2NameCache.get(clazz);

        if (className != null) {
            return className;
        }

        className = getNameWithoutCache(clazz);

        // Same logic as name2ClassCache: memory size should be controllable unless unusual code is present
        class2NameCache.putIfAbsent(clazz, className);

        return className;
    }

    private static String getNameWithoutCache(Class<?> clazz) {
        if (!clazz.isArray()) {
            return clazz.getName();
        }

        StringBuilder sb = new StringBuilder();
        while (clazz.isArray()) {
            sb.append("[]");
            clazz = clazz.getComponentType();
        }

        return clazz.getName() + sb;
    }

    public static Class<?> getPrimitiveClass(String name) {
        // check if is primitive class
        if (name.length() <= PRIMITIVE_CLASS_NAME_MAX_LENGTH) {
            int index = Arrays.binarySearch(PRIMITIVE_NAMES, name);
            if (index >= 0) {
                return PRIMITIVE_CLASSES[index];
            }
        }
        return null;
    }
}