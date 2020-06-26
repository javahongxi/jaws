package org.hongxi.summer.core.extension;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Spi {

    Scope scope() default Scope.PROTOTYPE;

}