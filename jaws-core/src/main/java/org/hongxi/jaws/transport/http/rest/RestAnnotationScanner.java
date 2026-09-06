package org.hongxi.jaws.transport.http.rest;

import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection-based scanner that detects Spring Web and JAX-RS annotations on
 * service interfaces and implementations, building {@link RestMapping} entries
 * for the {@link RestMappingRegistry}.
 * <p>
 * Annotation classes are detected via {@link Class#forName(String)} at runtime — no
 * compile-time dependency on Spring Web or JAX-RS is required. If neither
 * annotation library is on the classpath, the scanner silently skips all
 * annotation processing.
 * <p>
 * Supported annotations:
 * <table>
 *   <tr><th>Feature</th><th>Spring Web</th><th>JAX-RS</th></tr>
 *   <tr><td>Class path</td><td>{@code @RequestMapping}</td><td>{@code @Path}</td></tr>
 *   <tr><td>Method mapping</td><td>{@code @GetMapping}, {@code @PostMapping}, etc.</td>
 *       <td>{@code @GET}+{@code @Path}, {@code @POST}+{@code @Path}, etc.</td></tr>
 *   <tr><td>Path variable</td><td>{@code @PathVariable}</td><td>{@code @PathParam}</td></tr>
 *   <tr><td>Query param</td><td>{@code @RequestParam}</td><td>{@code @QueryParam}</td></tr>
 *   <tr><td>Request body</td><td>{@code @RequestBody}</td><td>complex type (auto-detected)</td></tr>
 * </table>
 *
 * @author shenhongxi
 */
public class RestAnnotationScanner {

    private static final Logger log = LoggerFactory.getLogger(RestAnnotationScanner.class);

    // Spring Web annotation classes
    private static final Class<? extends Annotation> SPRING_REQUEST_MAPPING;
    private static final Class<? extends Annotation> SPRING_GET_MAPPING;
    private static final Class<? extends Annotation> SPRING_POST_MAPPING;
    private static final Class<? extends Annotation> SPRING_PUT_MAPPING;
    private static final Class<? extends Annotation> SPRING_DELETE_MAPPING;
    private static final Class<? extends Annotation> SPRING_PATCH_MAPPING;
    private static final Class<? extends Annotation> SPRING_PATH_VARIABLE;
    private static final Class<? extends Annotation> SPRING_REQUEST_PARAM;
    private static final Class<? extends Annotation> SPRING_REQUEST_BODY;

    // JAX-RS annotation classes
    private static final Class<? extends Annotation> JAX_PATH;
    private static final Class<? extends Annotation> JAX_GET;
    private static final Class<? extends Annotation> JAX_POST;
    private static final Class<? extends Annotation> JAX_PUT;
    private static final Class<? extends Annotation> JAX_DELETE;
    private static final Class<? extends Annotation> JAX_PATCH;
    private static final Class<? extends Annotation> JAX_PATH_PARAM;
    private static final Class<? extends Annotation> JAX_QUERY_PARAM;

    private static final boolean SPRING_PRESENT;
    private static final boolean JAX_RS_PRESENT;

    static {
        // Spring Web
        SPRING_REQUEST_MAPPING = loadAnnotation("org.springframework.web.bind.annotation.RequestMapping");
        SPRING_GET_MAPPING = loadAnnotation("org.springframework.web.bind.annotation.GetMapping");
        SPRING_POST_MAPPING = loadAnnotation("org.springframework.web.bind.annotation.PostMapping");
        SPRING_PUT_MAPPING = loadAnnotation("org.springframework.web.bind.annotation.PutMapping");
        SPRING_DELETE_MAPPING = loadAnnotation("org.springframework.web.bind.annotation.DeleteMapping");
        SPRING_PATCH_MAPPING = loadAnnotation("org.springframework.web.bind.annotation.PatchMapping");
        SPRING_PATH_VARIABLE = loadAnnotation("org.springframework.web.bind.annotation.PathVariable");
        SPRING_REQUEST_PARAM = loadAnnotation("org.springframework.web.bind.annotation.RequestParam");
        SPRING_REQUEST_BODY = loadAnnotation("org.springframework.web.bind.annotation.RequestBody");

        // JAX-RS (jakarta.ws.rs or javax.ws.rs)
        JAX_PATH = loadFirstAnnotation("jakarta.ws.rs.Path", "javax.ws.rs.Path");
        JAX_GET = loadFirstAnnotation("jakarta.ws.rs.GET", "javax.ws.rs.GET");
        JAX_POST = loadFirstAnnotation("jakarta.ws.rs.POST", "javax.ws.rs.POST");
        JAX_PUT = loadFirstAnnotation("jakarta.ws.rs.PUT", "javax.ws.rs.PUT");
        JAX_DELETE = loadFirstAnnotation("jakarta.ws.rs.DELETE", "javax.ws.rs.DELETE");
        JAX_PATCH = loadFirstAnnotation("jakarta.ws.rs.PATCH", "javax.ws.rs.PATCH");
        JAX_PATH_PARAM = loadFirstAnnotation("jakarta.ws.rs.PathParam", "javax.ws.rs.PathParam");
        JAX_QUERY_PARAM = loadFirstAnnotation("jakarta.ws.rs.QueryParam", "javax.ws.rs.QueryParam");

        SPRING_PRESENT = SPRING_REQUEST_MAPPING != null;
        JAX_RS_PRESENT = JAX_PATH != null;

        if (!SPRING_PRESENT && !JAX_RS_PRESENT) {
            log.debug("Neither Spring Web nor JAX-RS annotations found on classpath; REST mapping disabled");
        }
    }

    /**
     * Scan the given interface and implementation class for REST annotations,
     * building mappings and registering them in the given registry.
     *
     * @param interfaceClass the service interface
     * @param implClass      the service implementation class
     * @param registry       the target registry
     */
    public static void scan(Class<?> interfaceClass, Class<?> implClass, RestMappingRegistry registry) {
        try {
            if (SPRING_PRESENT) {
                int count = scanSpringAnnotations(interfaceClass, implClass, registry);
                if (count > 0) {
                    log.info("scanned {} Spring Web REST mapping(s) from {}", count, interfaceClass.getName());
                    return;
                }
            }
            if (JAX_RS_PRESENT) {
                int count = scanJaxRsAnnotations(interfaceClass, implClass, registry);
                if (count > 0) {
                    log.info("scanned {} JAX-RS mapping(s) from {}", count, interfaceClass.getName());
                }
            }
        } catch (Throwable t) {
            log.warn("Failed to scan REST annotations from {}: {}", interfaceClass.getName(), t.getMessage(), t);
        }
    }

    // ========================== Spring Web scanning ==========================

    private static int scanSpringAnnotations(Class<?> interfaceClass, Class<?> implClass, RestMappingRegistry registry) {
        // class-level @RequestMapping path
        String classPath = "";
        Annotation classMapping = findAnnotation(interfaceClass, SPRING_REQUEST_MAPPING);
        if (classMapping == null) {
            classMapping = findAnnotation(implClass, SPRING_REQUEST_MAPPING);
        }
        if (classMapping != null) {
            classPath = extractFirstPath(classMapping);
        }

        int count = 0;
        for (Method ifMethod : interfaceClass.getMethods()) {
            // find the corresponding method on the impl class
            Method implMethod = findImplMethod(implClass, ifMethod);
            Method effectiveMethod = implMethod != null ? implMethod : ifMethod;

            // check for Spring mapping annotations on both interface and impl method
            SpringMethodMapping sm = resolveSpringMethodMapping(ifMethod, effectiveMethod);
            if (sm == null) {
                continue;
            }

            String fullPath = normalizePath(classPath + sm.path);
            PathPattern pathPattern = new PathPattern(fullPath);
            List<ParameterBinding> bindings = buildSpringParameterBindings(ifMethod);

            RestMapping mapping = new RestMapping(
                    sm.httpMethod, pathPattern,
                    interfaceClass.getName(), ifMethod.getName(), ifMethod,
                    bindings
            );
            registry.addMapping(mapping);
            count++;
        }
        return count;
    }

    private static SpringMethodMapping resolveSpringMethodMapping(Method ifMethod, Method implMethod) {
        // check composed annotations first (@GetMapping, @PostMapping, etc.)
        HttpMethod composed = findSpringComposedHttpMethod(ifMethod);
        if (composed == null) {
            composed = findSpringComposedHttpMethod(implMethod);
        }
        if (composed != null) {
            String path = extractSpringComposedPath(ifMethod);
            if (path == null) {
                path = extractSpringComposedPath(implMethod);
            }
            return new SpringMethodMapping(composed, path != null ? path : "");
        }

        // check @RequestMapping
        Annotation rm = findAnnotation(ifMethod, SPRING_REQUEST_MAPPING);
        if (rm == null) {
            rm = findAnnotation(implMethod, SPRING_REQUEST_MAPPING);
        }
        if (rm != null) {
            HttpMethod method = extractSpringRequestMethod(rm);
            String path = extractFirstPath(rm);
            return new SpringMethodMapping(method, path);
        }

        return null;
    }

    private static HttpMethod findSpringComposedHttpMethod(Method method) {
        if (SPRING_GET_MAPPING != null && method.getAnnotation(SPRING_GET_MAPPING) != null) return HttpMethod.GET;
        if (SPRING_POST_MAPPING != null && method.getAnnotation(SPRING_POST_MAPPING) != null) return HttpMethod.POST;
        if (SPRING_PUT_MAPPING != null && method.getAnnotation(SPRING_PUT_MAPPING) != null) return HttpMethod.PUT;
        if (SPRING_DELETE_MAPPING != null && method.getAnnotation(SPRING_DELETE_MAPPING) != null) return HttpMethod.DELETE;
        if (SPRING_PATCH_MAPPING != null && method.getAnnotation(SPRING_PATCH_MAPPING) != null) return HttpMethod.PATCH;
        return null;
    }

    private static String extractSpringComposedPath(Method method) {
        Annotation ann = null;
        if (SPRING_GET_MAPPING != null) ann = method.getAnnotation(SPRING_GET_MAPPING);
        if (ann == null && SPRING_POST_MAPPING != null) ann = method.getAnnotation(SPRING_POST_MAPPING);
        if (ann == null && SPRING_PUT_MAPPING != null) ann = method.getAnnotation(SPRING_PUT_MAPPING);
        if (ann == null && SPRING_DELETE_MAPPING != null) ann = method.getAnnotation(SPRING_DELETE_MAPPING);
        if (ann == null && SPRING_PATCH_MAPPING != null) ann = method.getAnnotation(SPRING_PATCH_MAPPING);
        if (ann == null) return null;
        // @GetMapping/@PostMapping etc. have value() returning String[]
        return extractAnnotationStringArrayValue(ann, "value");
    }

    private static List<ParameterBinding> buildSpringParameterBindings(Method method) {
        Parameter[] params = method.getParameters();
        Class<?>[] paramTypes = method.getParameterTypes();
        List<ParameterBinding> bindings = new ArrayList<>(params.length);

        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            Class<?> type = paramTypes[i];

            if (SPRING_PATH_VARIABLE != null) {
                Annotation pv = param.getAnnotation(SPRING_PATH_VARIABLE);
                if (pv != null) {
                    String name = extractAnnotationStringValue(pv, "value");
                    if (name == null || name.isEmpty()) {
                        name = extractAnnotationStringValue(pv, "name");
                    }
                    if (name == null || name.isEmpty()) {
                        name = param.getName();
                    }
                    bindings.add(new ParameterBinding(ParameterBinding.Source.PATH_VARIABLE, name, type));
                    continue;
                }
            }

            if (SPRING_REQUEST_PARAM != null) {
                Annotation rp = param.getAnnotation(SPRING_REQUEST_PARAM);
                if (rp != null) {
                    String name = extractAnnotationStringValue(rp, "value");
                    if (name == null || name.isEmpty()) {
                        name = extractAnnotationStringValue(rp, "name");
                    }
                    if (name == null || name.isEmpty()) {
                        name = param.getName();
                    }
                    bindings.add(new ParameterBinding(ParameterBinding.Source.QUERY_PARAM, name, type));
                    continue;
                }
            }

            if (SPRING_REQUEST_BODY != null && param.getAnnotation(SPRING_REQUEST_BODY) != null) {
                bindings.add(new ParameterBinding(ParameterBinding.Source.REQUEST_BODY, null, type));
                continue;
            }

            bindings.add(new ParameterBinding(ParameterBinding.Source.NONE, null, type));
        }
        return bindings;
    }

    // ========================== JAX-RS scanning ==========================

    private static int scanJaxRsAnnotations(Class<?> interfaceClass, Class<?> implClass, RestMappingRegistry registry) {
        // class-level @Path
        String classPath = "";
        Annotation classPathAnn = findAnnotation(interfaceClass, JAX_PATH);
        if (classPathAnn == null) {
            classPathAnn = findAnnotation(implClass, JAX_PATH);
        }
        if (classPathAnn != null) {
            classPath = extractAnnotationStringValue(classPathAnn, "value");
            if (classPath == null) classPath = "";
        }

        int count = 0;
        for (Method ifMethod : interfaceClass.getMethods()) {
            Method implMethod = findImplMethod(implClass, ifMethod);
            Method effectiveMethod = implMethod != null ? implMethod : ifMethod;

            HttpMethod httpMethod = resolveJaxRsHttpMethod(ifMethod, effectiveMethod);
            if (httpMethod == null) {
                continue;
            }

            // method-level @Path
            String methodPath = "";
            Annotation methodPathAnn = findAnnotation(ifMethod, JAX_PATH);
            if (methodPathAnn == null) {
                methodPathAnn = findAnnotation(effectiveMethod, JAX_PATH);
            }
            if (methodPathAnn != null) {
                methodPath = extractAnnotationStringValue(methodPathAnn, "value");
                if (methodPath == null) methodPath = "";
            }

            String fullPath = normalizePath(classPath + "/" + methodPath);
            PathPattern pathPattern = new PathPattern(fullPath);
            List<ParameterBinding> bindings = buildJaxRsParameterBindings(ifMethod);

            RestMapping mapping = new RestMapping(
                    httpMethod, pathPattern,
                    interfaceClass.getName(), ifMethod.getName(), ifMethod,
                    bindings
            );
            registry.addMapping(mapping);
            count++;
        }
        return count;
    }

    private static HttpMethod resolveJaxRsHttpMethod(Method ifMethod, Method implMethod) {
        if (hasAnnotation(ifMethod, JAX_GET) || hasAnnotation(implMethod, JAX_GET)) return HttpMethod.GET;
        if (hasAnnotation(ifMethod, JAX_POST) || hasAnnotation(implMethod, JAX_POST)) return HttpMethod.POST;
        if (hasAnnotation(ifMethod, JAX_PUT) || hasAnnotation(implMethod, JAX_PUT)) return HttpMethod.PUT;
        if (hasAnnotation(ifMethod, JAX_DELETE) || hasAnnotation(implMethod, JAX_DELETE)) return HttpMethod.DELETE;
        if (hasAnnotation(ifMethod, JAX_PATCH) || hasAnnotation(implMethod, JAX_PATCH)) return HttpMethod.PATCH;
        return null;
    }

    private static List<ParameterBinding> buildJaxRsParameterBindings(Method method) {
        Parameter[] params = method.getParameters();
        Class<?>[] paramTypes = method.getParameterTypes();
        List<ParameterBinding> bindings = new ArrayList<>(params.length);
        boolean hasBodyBinding = false;

        for (int i = 0; i < params.length; i++) {
            Parameter param = params[i];
            Class<?> type = paramTypes[i];

            if (JAX_PATH_PARAM != null) {
                Annotation pp = param.getAnnotation(JAX_PATH_PARAM);
                if (pp != null) {
                    String name = extractAnnotationStringValue(pp, "value");
                    if (name == null || name.isEmpty()) {
                        name = param.getName();
                    }
                    bindings.add(new ParameterBinding(ParameterBinding.Source.PATH_VARIABLE, name, type));
                    continue;
                }
            }

            if (JAX_QUERY_PARAM != null) {
                Annotation qp = param.getAnnotation(JAX_QUERY_PARAM);
                if (qp != null) {
                    String name = extractAnnotationStringValue(qp, "value");
                    if (name == null || name.isEmpty()) {
                        name = param.getName();
                    }
                    bindings.add(new ParameterBinding(ParameterBinding.Source.QUERY_PARAM, name, type));
                    continue;
                }
            }

            // JAX-RS: unannotated complex type is treated as request body
            if (!isSimpleType(type) && !hasBodyBinding) {
                bindings.add(new ParameterBinding(ParameterBinding.Source.REQUEST_BODY, null, type));
                hasBodyBinding = true;
                continue;
            }

            bindings.add(new ParameterBinding(ParameterBinding.Source.NONE, null, type));
        }
        return bindings;
    }

    // ========================== Utility methods ==========================

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Boolean.class
                || type == Integer.class
                || type == Long.class
                || type == Double.class
                || type == Float.class
                || type == Short.class
                || type == Byte.class
                || type == Character.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class;
    }

    private static Method findImplMethod(Class<?> implClass, Method ifMethod) {
        try {
            return implClass.getMethod(ifMethod.getName(), ifMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        // ensure leading slash
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // collapse double slashes
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        // remove trailing slash (unless it's just "/")
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> loadAnnotation(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            if (clazz.isAnnotation()) {
                return (Class<? extends Annotation>) clazz;
            }
        } catch (ClassNotFoundException e) {
            // not on classpath
        }
        return null;
    }

    private static Class<? extends Annotation> loadFirstAnnotation(String primary, String fallback) {
        Class<? extends Annotation> ann = loadAnnotation(primary);
        if (ann != null) return ann;
        return loadAnnotation(fallback);
    }

    private static <A extends Annotation> A findAnnotation(Class<?> clazz, Class<A> annotationType) {
        if (annotationType == null || clazz == null) return null;
        return clazz.getAnnotation(annotationType);
    }

    private static <A extends Annotation> A findAnnotation(java.lang.reflect.Method method, Class<A> annotationType) {
        if (annotationType == null || method == null) return null;
        return method.getAnnotation(annotationType);
    }

    private static boolean hasAnnotation(java.lang.reflect.Method method, Class<? extends Annotation> annotationType) {
        if (annotationType == null || method == null) return false;
        return method.getAnnotation(annotationType) != null;
    }

    private static boolean hasAnnotation(Class<?> clazz, Class<? extends Annotation> annotationType) {
        if (annotationType == null || clazz == null) return false;
        return clazz.getAnnotation(annotationType) != null;
    }

    private static String extractFirstPath(Annotation annotation) {
        // try "value" first, then "path"
        String value = extractAnnotationStringArrayValue(annotation, "value");
        if (value == null) {
            value = extractAnnotationStringArrayValue(annotation, "path");
        }
        return value != null ? value : "";
    }

    private static String extractAnnotationStringValue(Annotation annotation, String attribute) {
        try {
            Method m = annotation.annotationType().getMethod(attribute);
            Object value = m.invoke(annotation);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractAnnotationStringArrayValue(Annotation annotation, String attribute) {
        try {
            Method m = annotation.annotationType().getMethod(attribute);
            Object value = m.invoke(annotation);
            if (value instanceof String[] arr && arr.length > 0) {
                return arr[0];
            }
            if (value instanceof String s) {
                return s;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Extract the HTTP method from a Spring {@code @RequestMapping} annotation
     * by reading its {@code method} attribute.
     */
    private static HttpMethod extractSpringRequestMethod(Annotation requestMapping) {
        try {
            Method m = requestMapping.annotationType().getMethod("method");
            Object value = m.invoke(requestMapping);
            if (value instanceof Object[] arr && arr.length > 0) {
                // Spring's RequestMethod enum — use name()
                String name = arr[0].toString();
                return HttpMethod.valueOf(name);
            }
        } catch (Exception e) {
            // ignore
        }
        // default to GET if no method specified
        return HttpMethod.GET;
    }

    /**
     * Internal record for Spring method-level mapping resolution.
     */
    private record SpringMethodMapping(HttpMethod httpMethod, String path) {}
}
