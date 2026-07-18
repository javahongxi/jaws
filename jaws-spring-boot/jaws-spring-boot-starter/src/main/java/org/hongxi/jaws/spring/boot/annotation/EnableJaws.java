package org.hongxi.jaws.spring.boot.annotation;

import org.hongxi.jaws.spring.boot.EnableJawsRegistrar;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

/**
 * Enables Jaws RPC components as Spring Beans.
 * <p>
 * This annotation will scan the specified base packages for classes annotated with
 * {@link JawsService @JawsService}, register them as Spring beans, and create
 * corresponding {@code ServiceBean} definitions for service export.
 * <p>
 * Example usage:
 * <pre>
 * &#64;EnableJaws(scanBasePackages = "org.hongxi.jaws.sample")
 * &#64;SpringBootApplication
 * public class SampleProviderApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(SampleProviderApplication.class, args);
 *     }
 * }
 * </pre>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 *
 * @see JawsService
 * @see EnableJawsRegistrar
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Import(EnableJawsRegistrar.class)
public @interface EnableJaws {

    /**
     * Base packages to scan for annotated {@link JawsService @JawsService} classes.
     * <p>
     * Use {@link #scanBasePackageClasses()} for a type-safe alternative to String-based
     * package names.
     *
     * @return the base packages to scan
     */
    @AliasFor("basePackages")
    String[] scanBasePackages() default {};

    /**
     * Base packages to scan for annotated {@link JawsService @JawsService} classes.
     *
     * @return the base packages to scan
     */
    @AliasFor("scanBasePackages")
    String[] basePackages() default {};

    /**
     * Type-safe alternative to {@link #scanBasePackages()} for specifying the packages to
     * scan for annotated {@link JawsService @JawsService} classes. The package of each class
     * specified will be scanned.
     *
     * @return classes from the base packages to scan
     */
    Class<?>[] scanBasePackageClasses() default {};
}
