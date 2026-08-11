package org.hongxi.jaws.config.annotation;

import java.lang.annotation.*;

/**
 * Controls how configuration properties are exported into URL parameter maps
 * via {@code AbstractConfig.appendConfigParams}.
 * <p>
 * <b>Method level</b> (on getters): include or exclude individual properties.
 * Use {@link #key()} to override the parameter key name, {@link #excluded()} to skip
 * a getter, and {@link #required()} to enforce that the property must not be null or empty.
 * <p>
 * <b>Type level</b> (on classes): exclude all getters declared in the annotated class
 * from config parameter collection. Useful for Spring-layer subclasses (e.g. ServiceBean)
 * whose own properties should not leak into the RPC URL parameter map.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ConfigDesc {

    String key() default "";

    boolean excluded() default false;

    boolean required() default false;
}