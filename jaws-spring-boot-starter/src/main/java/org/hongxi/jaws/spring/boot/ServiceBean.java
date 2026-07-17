package org.hongxi.jaws.spring.boot;

import org.hongxi.jaws.config.ServiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * A Spring bean that wraps {@link ServiceConfig} to export a Jaws RPC service.
 * <p>
 * Each {@link org.hongxi.jaws.spring.boot.annotation.JawsService @JawsService} annotated class
 * gets its own {@code ServiceBean} instance, whose lifecycle is managed by Spring:
 * <ul>
 *   <li>{@link #afterPropertiesSet()} resolves the ref bean and registers as pending (does NOT export)</li>
 *   <li>Export is triggered later by {@link JawsBootstrap} on {@code ContextRefreshedEvent},
 *       ensuring all beans (including AOP proxies) are fully initialized</li>
 *   <li>{@link #destroy()} triggers the service unexport on context shutdown</li>
 * </ul>
 * <p>
 * This design follows Dubbo's ServiceBean pattern:
 * <ol>
 *   <li>{@code afterPropertiesSet()} only resolves ref and sets pending state</li>
 *   <li>Actual export is deferred to {@code ContextRefreshedEvent} via {@link JawsBootstrap}</li>
 *   <li>This ensures AOP proxies and all dependent beans are ready before export</li>
 * </ol>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 *
 * @see org.hongxi.jaws.spring.boot.annotation.JawsService
 * @see ServiceAnnotationPostProcessor
 * @see JawsBootstrap
 */
public class ServiceBean extends ServiceConfig<Object>
        implements InitializingBean, DisposableBean, ApplicationContextAware, BeanNameAware {

    private static final Logger log = LoggerFactory.getLogger(ServiceBean.class);

    private final String serviceBeanName;
    private final String refBeanName;
    private String beanName;
    private ApplicationContext applicationContext;
    private volatile boolean pending;

    /**
     * Creates a new ServiceBean.
     *
     * @param serviceBeanName the logical name for this service bean
     * @param refBeanName     the Spring bean name of the service implementation (ref)
     */
    public ServiceBean(String serviceBeanName, String refBeanName) {
        this.serviceBeanName = serviceBeanName;
        this.refBeanName = refBeanName;
    }

    public String getServiceBeanName() {
        return serviceBeanName;
    }

    public String getRefBeanName() {
        return refBeanName;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    public String getBeanName() {
        return this.beanName;
    }

    /**
     * Resolves the ref bean from the application context and marks this service as pending.
     * Does NOT call export here. Export is deferred to {@link JawsBootstrap} on
     * {@code ContextRefreshedEvent} after all singleton beans are fully initialized.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void afterPropertiesSet() {
        /* resolve the service implementation bean from context */
        Object ref = applicationContext.getBean(refBeanName);
        setRef((Object) ref);

        pending = true;
        log.info("[ServiceBean] registered service bean: name={}, interface={}, ref={}",
                serviceBeanName, getInterface().getName(), refBeanName);
    }

    /**
     * Exports this service. Called by {@link JawsBootstrap} after context refresh.
     */
    public void doExport() {
        if (pending) {
            pending = false;
            export();
            log.info("[ServiceBean] exported service: bean={}, interface={}, group={}, version={}",
                    serviceBeanName, getInterface().getName(), getGroup(), getVersion());
        }
    }

    @Override
    public void destroy() throws Exception {
        unexport();
        log.info("[ServiceBean] unexported service: bean={}, interface={}",
                serviceBeanName, getInterface().getName());
    }
}
