package org.hongxi.jaws.config.annotation;

import java.lang.annotation.*;

/**
 * Controls how configuration classes participate in URL parameter collection.
 * <p>
 * <b>Type level</b> (on classes): exclude all properties declared in the annotated
 * class from being collected by {@code AbstractConfig.collectParams}.  This is useful
 * for Spring-layer subclasses (e.g. {@code ServiceBean}) whose own properties should
 * not leak into the RPC URL parameter map.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigDesc {

    /**
     * Whether to exclude all properties of the annotated class from URL parameter collection.
     */
    boolean excluded() default false;
}
