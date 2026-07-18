package org.hongxi.jaws.spring.boot;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.ReferenceConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.hongxi.jaws.spring.boot.annotation.JawsReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link BeanPostProcessor} that scans for {@link JawsReference} annotated fields
 * and injects Jaws RPC service proxies.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class ReferenceAnnotationBeanPostProcessor implements BeanPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReferenceAnnotationBeanPostProcessor.class);

    private final BeanFactory beanFactory;
    private final List<ReferenceConfig<?>> referenceConfigs = new ArrayList<>();

    public ReferenceAnnotationBeanPostProcessor(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
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

    @SuppressWarnings("unchecked")
    private Object createReference(JawsReference jawsRef, Class<?> fieldType) {
        JawsProperties properties = beanFactory.getBean(JawsProperties.class);
        ProtocolConfig protocolConfig = beanFactory.getBean(ProtocolConfig.class);
        RegistryConfig registryConfig = beanFactory.getBean(RegistryConfig.class);

        Class<?> interfaceClass = (jawsRef.interfaceClass() != void.class)
                ? jawsRef.interfaceClass() : fieldType;
        String application = StringUtils.isNotBlank(jawsRef.application())
                ? jawsRef.application() : properties.getApplication().getName();

        ReferenceConfig<Object> refConfig = new ReferenceConfig<>();
        refConfig.setInterface((Class<Object>) interfaceClass);
        refConfig.setApplication(application);
        refConfig.setProtocol(protocolConfig);
        refConfig.setRegistry(registryConfig);

        /* group: annotation > global */
        String group = StringUtils.isNotBlank(jawsRef.group())
                ? jawsRef.group() : properties.getGroup();
        if (StringUtils.isNotBlank(group)) {
            refConfig.setGroup(group);
        }

        /* version: annotation > global */
        String version = StringUtils.isNotBlank(jawsRef.version())
                ? jawsRef.version() : properties.getVersion();
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
        String check = StringUtils.isNotBlank(jawsRef.check())
                ? jawsRef.check() : properties.getReference().getCheck();
        if (StringUtils.isNotBlank(check)) {
            refConfig.setCheck(check);
        }

        /* retries */
        if (properties.getReference().getRetries() != null) {
            refConfig.setRetries(properties.getReference().getRetries());
        }

        /* directUrl */
        if (StringUtils.isNotBlank(jawsRef.directUrl())) {
            refConfig.setDirectUrl(jawsRef.directUrl());
        }

        Object proxy = refConfig.getRef();
        referenceConfigs.add(refConfig);

        log.info("[JawsReferenceBPP] created reference: interface={}, group={}, version={}",
                interfaceClass.getName(), refConfig.getGroup(), refConfig.getVersion());

        return proxy;
    }

    @Override
    public void destroy() {
        for (ReferenceConfig<?> referenceConfig : referenceConfigs) {
            try {
                referenceConfig.destroy();
            } catch (Exception e) {
                log.warn("[JawsReferenceBPP] failed to destroy reference", e);
            }
        }
    }
}
