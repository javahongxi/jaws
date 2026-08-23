package org.hongxi.jaws.common.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a jaws SPI extension point, the counterpart of
 * Dubbo's {@code @SPI}. Only interfaces carrying this annotation can be
 * loaded by {@link ExtensionLoader}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Spi {

    /**
     * Whether extensions of this type are singletons. When true, one
     * instance per extension name is created lazily and cached; when
     * false, a new instance is created on every
     * {@link ExtensionLoader#getExtension} call.
     */
    boolean singleton() default false;
}
