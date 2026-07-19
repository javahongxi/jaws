package org.hongxi.jaws.spring.boot.annotation;

import java.lang.annotation.*;

/**
 * Marks a field to be injected with a Jaws RPC service proxy.
 * <p>
 * The field type must be a service interface. A proxy will be created
 * and injected automatically when the Spring context starts.
 * <p>
 * Example usage:
 * <pre>
 * &#64;Component
 * public class MyController {
 *
 *     &#64;JawsReference
 *     private DemoService demoService;
 * }
 * </pre>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface JawsReference {

    /**
     * Service interface class. Defaults to the field type.
     */
    Class<?> interfaceClass() default void.class;

    /**
     * Service group. If empty, uses the global group config.
     */
    String group() default "";

    /**
     * Service version. If empty, uses the global version config.
     */
    String version() default "";

    /**
     * Request timeout in milliseconds. 0 means use global default.
     */
    int requestTimeout() default 0;

    /**
     * Whether to check if the service is available on startup.
     */
    String check() default "";

    /**
     * Direct connect URL (bypasses registry). Format: "host:port".
     */
    String directUrl() default "";

    /**
     * Whether to use generic invocation (no interface JAR dependency).
     * When true, the field type should be {@link org.hongxi.jaws.rpc.GenericService}.
     */
    boolean generic() default false;

    /**
     * The real service interface name for generic invocation.
     * Required when {@link #generic()} is true.
     */
    String serviceInterface() default "";

    /**
     * Application name. If empty, uses the global application name.
     */
    String application() default "";

    /**
     * Per-method configuration (timeout, retries, etc.).
     */
    Method[] methods() default {};
}
