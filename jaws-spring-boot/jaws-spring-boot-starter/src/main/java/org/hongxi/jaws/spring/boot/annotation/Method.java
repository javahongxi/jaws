package org.hongxi.jaws.spring.boot.annotation;

import java.lang.annotation.*;

/**
 * Method-level configuration for RPC service invocation.
 * <p>
 * Used within {@link JawsReference#methods()} to set per-method
 * timeout, retries, etc.
 * <p>
 * Example usage:
 * <pre>
 * &#64;JawsReference(methods = {
 *     &#64;Method(name = "hello", timeout = 1000, retries = 2),
 *     &#64;Method(name = "getUsers", timeout = 5000)
 * })
 * private DemoService demoService;
 * </pre>
 * <p>
 * Created by shenhongxi on 2026/7/19.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface Method {

    /**
     * Method name (required).
     */
    String name();

    /**
     * Request timeout in milliseconds. 0 means use interface-level default.
     */
    int timeout() default 0;

    /**
     * Failure retry count. -1 means use interface-level default.
     */
    int retries() default -1;
}
