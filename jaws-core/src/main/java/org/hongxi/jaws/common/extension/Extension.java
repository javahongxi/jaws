package org.hongxi.jaws.common.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an SPI extension implementation with a logical name
 * and an optional numeric identifier.
 *
 * <p>The {@link #value()} is used by {@link ExtensionLoader} to register
 * and look up the extension. The {@link #number()} is an optional fixed
 * identifier used when the protocol needs to select an implementation by
 * number (e.g. serialization type embedded in the protocol header).
 *
 * @author shenhongxi
 * @see ExtensionLoader
 * @see Spi
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Extension {
    String value();

    /**
     * Optional numeric identifier for the extension, used when the protocol
     * needs to select an implementation by number (e.g. serialization type in header).
     * Value must be in range 0-31. Default -1 means not assigned.
     */
    int number() default -1;
}
