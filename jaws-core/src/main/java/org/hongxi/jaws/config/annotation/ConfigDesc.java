package org.hongxi.jaws.config.annotation;

import java.lang.annotation.*;

/**
 * 对配置参数的描述，用于通过配置方法进行配置属性自动装载
 *
 * Created by shenhongxi on 2021/3/5.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigDesc {

    String key() default "";

    boolean excluded() default false;

    boolean required() default false;
}