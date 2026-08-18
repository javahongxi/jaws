package org.hongxi.jaws.common.extension;

import java.lang.annotation.*;

/**
 * When an SPI has multiple implementations, this annotation allows filtering
 * and sorting them based on activation conditions before returning the result.
 * <p>
 * Created by shenhongxi on 2020/7/25.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Activation {

    /**
     * Sort order for this activation. Lower values come first in the returned list.
     * Recommended range: 0-100.
     */
    int order() default 20;

    /**
     * Activation match values, used to filter SPI implementations when calling getExtensions.
     * The implementation is included only when this array contains the search key.
     * Common values: "service" (provider side), "reference" (consumer side).
     */
    String[] value() default "";
}