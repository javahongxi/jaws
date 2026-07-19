package org.hongxi.jaws.spring.boot;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.spring.boot.annotation.JawsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Scans for {@link JawsService @JawsService} annotated classes and registers both
 * the implementation bean definitions and corresponding {@link ServiceBean} definitions,
 * following Dubbo's {@code ServiceAnnotationPostProcessor} pattern.
 * <p>
 * This is a {@link BeanDefinitionRegistryPostProcessor} that:
 * <ol>
 *   <li><b>Package scanning</b>: scans the classpath for {@code @JawsService} classes
 *       in the configured base packages and registers them as Spring beans</li>
 *   <li><b>Bean definition scanning</b>: checks existing bean definitions for
 *       {@code @JawsService} annotation (for classes already registered via component scan or other means)</li>
 * </ol>
 * For each discovered {@code @JawsService} class, a corresponding {@link ServiceBean} definition
 * is registered with {@code ref} pointing to the implementation bean.
 * <p>
 * The actual export is NOT triggered here. It is deferred to {@link JawsBootstrap} which listens
 * for {@code ContextRefreshedEvent} and triggers all pending {@link ServiceBean}s to export.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 *
 * @see ServiceBean
 * @see JawsBootstrap
 * @see EnableJawsRegistrar
 */
public class ServiceAnnotationPostProcessor
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanClassLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(ServiceAnnotationPostProcessor.class);

    private final Set<String> packagesToScan;

    private Environment environment;
    private ClassLoader classLoader;

    /**
     * Creates a new {@link ServiceAnnotationPostProcessor} with the specified base packages to scan.
     *
     * @param packagesToScan the base packages to scan for {@code @JawsService} annotated classes
     */
    public ServiceAnnotationPostProcessor(Set<String> packagesToScan) {
        this.packagesToScan = packagesToScan;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        /* scan base packages for @JawsService classes and register them as beans */
        scanAndRegisterImplBeans(registry);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        /* find all @JawsService beans (from package scan or existing bean definitions) and register ServiceBeans */
        Map<String, Class<?>> serviceBeanMap = findServiceBeans(beanFactory);
        if (serviceBeanMap.isEmpty()) {
            return;
        }
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        for (Map.Entry<String, Class<?>> entry : serviceBeanMap.entrySet()) {
            String refBeanName = entry.getKey();
            Class<?> beanClass = entry.getValue();
            JawsService jawsService = beanClass.getAnnotation(JawsService.class);
            registerServiceBean(registry, refBeanName, jawsService, beanClass);
        }
    }

    /**
     * Scans the configured base packages for classes annotated with {@link JawsService}
     * and registers them as Spring bean definitions (since they no longer carry {@code @Service}).
     */
    private void scanAndRegisterImplBeans(BeanDefinitionRegistry registry) {
        if (packagesToScan.isEmpty()) {
            return;
        }

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(JawsService.class));

        for (String basePackage : packagesToScan) {
            Set<BeanDefinition> candidates = scanner.findCandidateComponents(basePackage);
            for (BeanDefinition candidate : candidates) {
                String beanClassName = candidate.getBeanClassName();
                if (beanClassName == null) {
                    continue;
                }
                /* generate a bean name from the short class name */
                String beanName = ClassUtils.getShortName(beanClassName);
                if (!registry.containsBeanDefinition(beanName)) {
                    /* register the impl class as a Spring bean */
                    registry.registerBeanDefinition(beanName, candidate);
                    log.info("[ServiceAnnotationPostProcessor] registered @JawsService impl bean: name={}, class={}",
                            beanName, beanClassName);
                }
            }
        }
    }

    /**
     * Finds all bean definitions whose class is annotated with {@link JawsService}.
     *
     * @return a map of bean name to resolved bean class
     */
    private Map<String, Class<?>> findServiceBeans(ConfigurableListableBeanFactory beanFactory) {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
            String beanClassName = beanDefinition.getBeanClassName();
            if (beanClassName == null) {
                continue;
            }
            try {
                Class<?> beanClass = Class.forName(beanClassName, false, classLoader);
                if (beanClass.getAnnotation(JawsService.class) != null) {
                    result.put(beanName, beanClass);
                }
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                /* skip unresolvable classes */
            }
        }
        return result;
    }

    /**
     * Builds and registers a {@link ServiceBean} {@link BeanDefinition} for the given
     * {@code @JawsService} annotated bean.
     *
     * @param registry    the bean definition registry
     * @param refBeanName the Spring bean name of the implementation
     * @param jawsService the {@code @JawsService} annotation instance
     * @param beanClass   the implementation class (may be {@code null} if unresolvable)
     */
    private void registerServiceBean(BeanDefinitionRegistry registry,
                                         String refBeanName,
                                         JawsService jawsService,
                                         Class<?> beanClass) {
        Class<?> interfaceClass = resolveInterfaceClass(jawsService, beanClass);
        if (interfaceClass == null) {
            throw new IllegalStateException(
                    "Cannot resolve interface class for @JawsService bean '" + refBeanName
                            + "'. Please specify interfaceClass explicitly.");
        }
        String serviceBeanName = generateServiceBeanName(interfaceClass, refBeanName);

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ServiceBean.class);

        /* constructor args: serviceBeanName, refBeanName */
        builder.addConstructorArgValue(serviceBeanName);
        builder.addConstructorArgValue(refBeanName);

        /* interface class */
        builder.addPropertyValue("interface", interfaceClass);

        /* export: annotation > global service config */
        String export = StringUtils.isNotBlank(jawsService.export())
                ? jawsService.export() : environment.getProperty("jaws.service.export", "jaws:-1");
        builder.addPropertyValue("export", export);

        /* application name */
        String application = StringUtils.isNotBlank(jawsService.application())
                ? jawsService.application() : environment.getProperty("jaws.application.name");
        if (StringUtils.isNotBlank(application)) {
            builder.addPropertyValue("application", application);
        }

        /* group: annotation > global */
        String group = StringUtils.isNotBlank(jawsService.group())
                ? jawsService.group() : environment.getProperty("jaws.group");
        if (StringUtils.isNotBlank(group)) {
            builder.addPropertyValue("group", group);
        }

        /* version: annotation > global */
        String version = StringUtils.isNotBlank(jawsService.version())
                ? jawsService.version() : environment.getProperty("jaws.version");
        if (StringUtils.isNotBlank(version)) {
            builder.addPropertyValue("version", version);
        }

        /* shareChannel */
        String shareChannel = environment.getProperty("jaws.service.share-channel", "true");
        if ("true".equalsIgnoreCase(shareChannel)) {
            builder.addPropertyValue("shareChannel", true);
        }

        /* token: annotation > global */
        String token = StringUtils.isNotBlank(jawsService.token())
                ? jawsService.token() : environment.getProperty("jaws.service.token");
        if (StringUtils.isNotBlank(token)) {
            builder.addPropertyValue("token", token);
        }

        /* reference to ProtocolConfig and RegistryConfig beans */
        builder.addPropertyReference("protocol", "protocolConfig");
        builder.addPropertyReference("registry", "registryConfig");

        builder.setLazyInit(false);

        registry.registerBeanDefinition(serviceBeanName, builder.getBeanDefinition());

        log.info("[ServiceAnnotationPostProcessor] registered ServiceBean: name={}, interface={}, ref={}",
                serviceBeanName, interfaceClass.getName(), refBeanName);
    }

    /**
     * Resolves the service interface class from the annotation or the bean class.
     * <p>
     * If {@code interfaceClass} is explicitly specified in the annotation, it is used directly.
     * Otherwise, the first non-marker interface implemented by the bean class is returned.
     *
     * @param jawsService the annotation instance
     * @param beanClass   the implementation class (may be {@code null})
     * @return the resolved interface class, or {@code null} if unresolvable
     */
    private Class<?> resolveInterfaceClass(JawsService jawsService, Class<?> beanClass) {
        Class<?> interfaceClass = jawsService.interfaceClass();
        if (interfaceClass != void.class) {
            return interfaceClass;
        }
        if (beanClass == null) {
            return null;
        }
        Class<?>[] interfaces = beanClass.getInterfaces();
        for (Class<?> iface : interfaces) {
            /* skip common marker interfaces */
            if (iface == java.io.Serializable.class || iface == java.io.Closeable.class
                    || iface == AutoCloseable.class || iface == Cloneable.class
                    || iface == Comparable.class) {
                continue;
            }
            return iface;
        }
        return null;
    }

    /**
     * Generates the bean name for a {@link ServiceBean}.
     */
    private String generateServiceBeanName(Class<?> interfaceClass, String refBeanName) {
        return "serviceBean_" + interfaceClass.getSimpleName() + "_" + refBeanName;
    }
}
