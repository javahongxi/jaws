package org.hongxi.jaws.common.extension;

import java.lang.annotation.*;

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
     * Instance scope for extensions of this type: {@link Scope#SINGLETON}
     * shares one instance per extension name, while {@link Scope#PROTOTYPE}
     * creates a new instance on every {@link ExtensionLoader#getExtension}.
     */
    Scope scope() default Scope.PROTOTYPE;
}