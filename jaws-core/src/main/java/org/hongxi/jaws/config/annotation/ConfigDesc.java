package org.hongxi.jaws.config.annotation;

import java.lang.annotation.*;

/**
 * Annotates getter methods in configuration classes to control how properties
 * are exported into URL parameter maps via {@code AbstractConfig.appendConfigParams}.
 * <p>
 * By default, all public getter methods returning primitive/wrapper types are included.
 * Use {@link #key()} to override the parameter key name, {@link #excluded()} to skip
 * a getter, and {@link #required()} to enforce that the property must not be null or empty.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigDesc {

    String key() default "";

    boolean excluded() default false;

    boolean required() default false;
}