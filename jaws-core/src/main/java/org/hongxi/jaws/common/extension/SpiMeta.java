package org.hongxi.jaws.common.extension;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface SpiMeta {
    String name();

    /**
     * Optional numeric identifier for the SPI extension, used when the protocol
     * needs to select an implementation by number (e.g. serialization type in header).
     * Value must be in range 0-31. Default -1 means not assigned.
     */
    int number() default -1;
}