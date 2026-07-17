package org.hongxi.jaws.spring.boot;

import org.hongxi.jaws.config.ProtocolConfig;
import org.hongxi.jaws.config.RegistryConfig;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot Auto-configuration for Jaws RPC framework.
 * <p>
 * Provides:
 * <ul>
 *   <li>Automatic ProtocolConfig and RegistryConfig creation from application properties</li>
 *   <li>{@link JawsBootstrap} that triggers service export on {@code ContextRefreshedEvent}
 *       (following Dubbo's bootstrap pattern)</li>
 *   <li>{@link ReferenceAnnotationBeanPostProcessor} for service reference injection (consumer side)</li>
 * </ul>
 * <p>
 * Note: {@link ServiceAnnotationPostProcessor} is NOT created here. It is registered by
 * {@link EnableJawsRegistrar} when {@code @EnableJaws} is present, with the scan base packages
 * passed via constructor (following Dubbo's pattern).
 * <p>
 * The provider and consumer sides demonstrate different Spring integration patterns:
 * <ul>
 *   <li><b>Provider</b>: each {@code @JawsService} gets a dedicated {@link ServiceBean}
 *       (a proper Spring bean extending {@code ServiceConfig}) with full lifecycle management</li>
 *   <li><b>Consumer</b>: {@code @JawsReference} proxies are injected into existing beans
 *       via field injection by the BeanPostProcessor</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
@AutoConfiguration
@EnableConfigurationProperties(JawsProperties.class)
public class JawsAutoConfiguration {

    private final JawsProperties properties;

    public JawsAutoConfiguration(JawsProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProtocolConfig protocolConfig() {
        JawsProperties.Protocol protocolProps = properties.getProtocol();
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName(protocolProps.getName());
        protocolConfig.setId(protocolProps.getName());
        protocolConfig.setEndpointFactory(protocolProps.getEndpointFactory());
        protocolConfig.setSerialization(protocolProps.getSerialization());
        if (protocolProps.getRequestTimeout() != null) {
            protocolConfig.setRequestTimeout(protocolProps.getRequestTimeout());
        }
        if (protocolProps.getCodec() != null) {
            protocolConfig.setCodec(protocolProps.getCodec());
        }
        return protocolConfig;
    }

    @Bean
    @ConditionalOnMissingBean
    public RegistryConfig registryConfig() {
        JawsProperties.Registry registryProps = properties.getRegistry();
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setName("defaultRegistry");
        registryConfig.setId(registryConfig.getName());
        registryConfig.setRegProtocol(registryProps.getProtocol());
        registryConfig.setAddress(registryProps.getAddress());
        if (registryProps.getPort() != null) {
            registryConfig.setPort(registryProps.getPort());
        }
        if (registryProps.getConnectTimeout() != null) {
            registryConfig.setConnectTimeout(registryProps.getConnectTimeout());
        }
        if (registryProps.getRegistrySessionTimeout() != null) {
            registryConfig.setRegistrySessionTimeout(registryProps.getRegistrySessionTimeout());
        }
        if (registryProps.getRegistryRetryPeriod() != null) {
            registryConfig.setRegistryRetryPeriod(registryProps.getRegistryRetryPeriod());
        }
        return registryConfig;
    }

    @Bean
    public JawsBootstrap jawsBootstrap() {
        return new JawsBootstrap();
    }

    @Bean
    public static ReferenceAnnotationBeanPostProcessor referenceAnnotationBeanPostProcessor(BeanFactory beanFactory) {
        return new ReferenceAnnotationBeanPostProcessor(beanFactory);
    }
}
