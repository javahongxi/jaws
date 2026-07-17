package org.hongxi.jaws.spring.boot;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link ImportBeanDefinitionRegistrar} that processes {@link EnableJaws @EnableJaws}
 * annotation attributes and registers a {@link ServiceAnnotationPostProcessor}
 * with the resolved scan base packages.
 * <p>
 * The post-processor will then scan the specified packages for classes annotated with
 * {@code @JawsService}, register them as Spring beans, and create corresponding
 * {@link ServiceBean} definitions.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 *
 * @see EnableJaws
 * @see ServiceAnnotationPostProcessor
 */
public class EnableJawsRegistrar implements ImportBeanDefinitionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(EnableJawsRegistrar.class);

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableJaws.class.getName()));
        if (attributes == null) {
            return;
        }

        Set<String> packagesToScan = resolvePackagesToScan(attributes, importingClassMetadata);
        if (packagesToScan.isEmpty()) {
            log.warn("[EnableJawsRegistrar] no base packages specified in @EnableJaws, skipping");
            return;
        }

        if (!registry.containsBeanDefinition("serviceAnnotationPostProcessor")) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder
                    .genericBeanDefinition(ServiceAnnotationPostProcessor.class);
            builder.addConstructorArgValue(packagesToScan);
            builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition("serviceAnnotationPostProcessor",
                    builder.getBeanDefinition());
        }

        log.info("[EnableJawsRegistrar] registered serviceAnnotationPostProcessor with packages: {}",
                packagesToScan);
    }

    private Set<String> resolvePackagesToScan(AnnotationAttributes attributes,
                                              AnnotationMetadata importingClassMetadata) {
        Set<String> packages = new LinkedHashSet<>();

        /* scanBasePackages / basePackages (they are aliases) */
        String[] scanBasePackages = attributes.getStringArray("scanBasePackages");
        for (String pkg : scanBasePackages) {
            if (StringUtils.hasText(pkg)) {
                packages.add(pkg);
            }
        }

        /* scanBasePackageClasses */
        Class<?>[] classes = attributes.getClassArray("scanBasePackageClasses");
        for (Class<?> clazz : classes) {
            packages.add(ClassUtils.getPackageName(clazz));
        }

        /* fallback: use the package of the annotated class */
        if (packages.isEmpty()) {
            packages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
        }

        return packages;
    }
}
