package org.hongxi.jaws.spring.boot.annotation;

import java.lang.annotation.*;

/**
 * Marks a class as a Jaws RPC service to be exported.
 * <p>
 * The annotated class must implement at least one service interface.
 * If {@link #interfaceClass()} is not specified, the first non-marker interface
 * implemented by the class will be used automatically.
 * <p>
 * Classes annotated with {@code @JawsService} will be scanned and registered as Spring beans
 * when {@link org.hongxi.jaws.spring.boot.annotation.EnableJaws @EnableJaws} is present.
 * <p>
 * Example usage:
 * <pre>
 * // interfaceClass auto-detected from DemoServiceImpl's interfaces
 * &#64;JawsService
 * public class DemoServiceImpl implements DemoService {
 *     // ...
 * }
 *
 * // or specify explicitly
 * &#64;JawsService(interfaceClass = DemoService.class)
 * public class DemoServiceImpl implements DemoService {
 *     // ...
 * }
 * </pre>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JawsService {

    /**
     * Service interface class.
     * <p>
     * If not specified (defaults to {@code void.class}), the first non-marker interface
     * implemented by the annotated class will be used automatically.
     */
    Class<?> interfaceClass() default void.class;

    /**
     * Export protocol and port, format: "protocol:port".
     * Use "jaws:-1" for dynamic port allocation.
     * If empty, defaults to the global protocol config.
     */
    String export() default "";

    /**
     * Service group for routing isolation.
     */
    String group() default "";

    /**
     * Service version.
     */
    String version() default "";

    /**
     * Application name. If empty, uses the global application name.
     */
    String application() default "";
}
