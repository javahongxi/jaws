package org.hongxi.jaws.spring.boot;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.config.MethodConfig;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.hongxi.jaws.spring.boot.annotation.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.util.StringValueResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link BeanPostProcessor} that scans for {@link JawsReference} annotated fields
 * and injects Jaws RPC service proxies.
 * <p>
 * Every String-valued annotation attribute ({@code directUrl}, {@code group},
 * {@code version}, {@code application}, {@code serviceInterface}) supports
 * {@code ${...}} property placeholders, resolved against the Spring Environment
 * (e.g. {@code directUrl = "${sample.wire.address}"}). Primitive-valued attributes
 * ({@code requestTimeout}, {@code check}, {@code generic}) cannot carry a
 * placeholder by construction, so they are read as written.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class ReferenceAnnotationBeanPostProcessor implements BeanPostProcessor, DisposableBean, EmbeddedValueResolverAware {

    private static final Logger log = LoggerFactory.getLogger(ReferenceAnnotationBeanPostProcessor.class);

    private final BeanFactory beanFactory;
    private final List<ReferenceConfig<?>> referenceConfigs = new ArrayList<>();
    private StringValueResolver embeddedValueResolver;

    public ReferenceAnnotationBeanPostProcessor(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.embeddedValueResolver = resolver;
    }

    /**
     * Resolve {@code ${...}} placeholders in an annotation attribute value.
     */
    private String resolvePlaceholder(String value) {
        if (embeddedValueResolver == null || StringUtils.isBlank(value)) {
            return value;
        }
        /* keep the raw value when it contains an unresolvable placeholder */
        try {
            return embeddedValueResolver.resolveStringValue(value);
        } catch (IllegalArgumentException e) {
            log.warn("failed to resolve placeholder in value [{}], using it as-is", value);
            return value;
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> beanClass = bean.getClass();
        for (var field : beanClass.getDeclaredFields()) {
            JawsReference jawsRef = field.getAnnotation(JawsReference.class);
            if (jawsRef == null) {
                continue;
            }
            try {
                Object proxy = createReference(jawsRef, field.getType());
                field.setAccessible(true);
                field.set(bean, proxy);
            } catch (Exception e) {
                throw new RuntimeException(String.format("Failed to inject @JawsReference on field %s.%s",
                        beanClass.getSimpleName(), field.getName()), e);
            }
        }
        return bean;
    }

    private Object createReference(JawsReference jawsRef, Class<?> fieldType) {
        JawsProperties properties = beanFactory.getBean(JawsProperties.class);
        ProtocolConfig protocolConfig = beanFactory.getBean(ProtocolConfig.class);
        RegistryConfig registryConfig = beanFactory.getBean(RegistryConfig.class);

        ReferenceConfig<Object> refConfig = toReferenceConfig(jawsRef, fieldType,
                properties, protocolConfig, registryConfig);

        Object proxy = refConfig.getRef();
        referenceConfigs.add(refConfig);

        log.info("created reference: interface={}, group={}, version={}",
                refConfig.getInterface().getName(), refConfig.getGroup(), refConfig.getVersion());

        return proxy;
    }

    /**
     * Map annotation attributes onto a {@link ReferenceConfig} (precedence:
     * annotation &gt; global properties), resolving {@code ${...}} placeholders on
     * the way. Kept apart from {@link #createReference} so the mapping can be
     * asserted without creating an RPC proxy.
     */
    @SuppressWarnings("unchecked")
    ReferenceConfig<Object> toReferenceConfig(JawsReference jawsRef, Class<?> fieldType,
                                              JawsProperties properties, ProtocolConfig protocolConfig,
                                              RegistryConfig registryConfig) {
        Class<?> interfaceClass = (jawsRef.interfaceClass() != void.class)
                ? jawsRef.interfaceClass() : fieldType;
        String application = StringUtils.isNotBlank(jawsRef.application())
                ? resolvePlaceholder(jawsRef.application()) : properties.getApplication().getName();

        ReferenceConfig<Object> refConfig = new ReferenceConfig<>();
        refConfig.setInterface((Class<Object>) interfaceClass);
        refConfig.setApplication(application);
        refConfig.setProtocol(protocolConfig);
        refConfig.setRegistry(registryConfig);

        /* group: annotation > global */
        String group = StringUtils.isNotBlank(jawsRef.group())
                ? resolvePlaceholder(jawsRef.group()) : properties.getReference().getGroup();
        if (StringUtils.isNotBlank(group)) {
            refConfig.setGroup(group);
        }

        /* version: annotation > global */
        String version = StringUtils.isNotBlank(jawsRef.version())
                ? resolvePlaceholder(jawsRef.version()) : properties.getReference().getVersion();
        if (StringUtils.isNotBlank(version)) {
            refConfig.setVersion(version);
        }

        /* requestTimeout: annotation > global reference > global protocol */
        int timeout = jawsRef.requestTimeout();
        if (timeout <= 0 && properties.getReference().getRequestTimeout() != null) {
            timeout = properties.getReference().getRequestTimeout();
        }
        if (timeout > 0) {
            refConfig.setRequestTimeout(timeout);
        }

        /* check: annotation > global reference */
        if (jawsRef.check()) {
            refConfig.setCheck(true);
        } else if (properties.getReference().getCheck() != null) {
            refConfig.setCheck(properties.getReference().getCheck());
        }

        /* retries */
        if (properties.getReference().getRetries() != null) {
            refConfig.setRetries(properties.getReference().getRetries());
        }

        /* directUrl */
        String directUrl = resolvePlaceholder(jawsRef.directUrl());
        if (StringUtils.isNotBlank(directUrl)) {
            refConfig.setDirectUrl(directUrl);
        }

        /* generic invocation */
        if (jawsRef.generic()) {
            refConfig.setGeneric(true);
            String serviceInterface = resolvePlaceholder(jawsRef.serviceInterface());
            if (StringUtils.isNotBlank(serviceInterface)) {
                refConfig.setServiceInterface(serviceInterface);
            } else if (jawsRef.interfaceClass() != void.class) {
                refConfig.setServiceInterface(jawsRef.interfaceClass().getName());
            }
        }

        /* method-level config */
        if (jawsRef.methods().length > 0) {
            List<MethodConfig> methodConfigs = new ArrayList<>();
            for (Method methodAnno : jawsRef.methods()) {
                MethodConfig mc = new MethodConfig();
                mc.setName(methodAnno.name());
                if (methodAnno.timeout() > 0) {
                    mc.setRequestTimeout(methodAnno.timeout());
                }
                if (methodAnno.retries() >= 0) {
                    mc.setRetries(methodAnno.retries());
                }
                methodConfigs.add(mc);
            }
            refConfig.setMethods(methodConfigs);
        }

        return refConfig;
    }

    @Override
    public void destroy() {
        for (ReferenceConfig<?> referenceConfig : referenceConfigs) {
            try {
                referenceConfig.destroy();
            } catch (Exception e) {
                log.warn("failed to destroy reference", e);
            }
        }
    }
}
