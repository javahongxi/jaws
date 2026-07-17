package org.hongxi.jaws.spring.boot;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.switcher.JawsSwitcherUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;

import java.util.Map;

/**
 * Bootstrap listener for Jaws RPC framework lifecycle, following Dubbo's
 * {@code DubboBootstrapApplicationListener} pattern.
 * <p>
 * Listens to Spring context events to coordinate service export/unexport:
 * <ul>
 *   <li>{@link ContextRefreshedEvent}: triggers all pending {@link ServiceBean}s to export,
 *       ensuring all singleton beans (including AOP proxies) are fully initialized</li>
 *   <li>{@link ContextClosedEvent}: triggers unexport and cleanup</li>
 * </ul>
 * <p>
 * This separation of bean registration (in {@link ServiceAnnotationPostProcessor}) and
 * export triggering (here, on context refresh) is the key design from Dubbo that
 * avoids AOP proxy loss and partial export issues.
 * <p>
 * Created by shenhongxi on 2026/7/17.
 *
 * @see ServiceBean
 * @see ServiceAnnotationPostProcessor
 */
public class JawsBootstrap implements ApplicationListener<ApplicationContextEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JawsBootstrap.class);

    private volatile boolean exported = false;
    private volatile boolean stopped = false;

    @Override
    public void onApplicationEvent(ApplicationContextEvent event) {
        if (event instanceof ContextRefreshedEvent) {
            onContextRefreshed((ContextRefreshedEvent) event);
        } else if (event instanceof ContextClosedEvent) {
            onContextClosed((ContextClosedEvent) event);
        }
    }

    private void onContextRefreshed(ContextRefreshedEvent event) {
        if (exported) {
            return;
        }
        exported = true;

        Map<String, ServiceBean> serviceBeans =
                event.getApplicationContext().getBeansOfType(ServiceBean.class);
        for (ServiceBean serviceBean : serviceBeans.values()) {
            try {
                serviceBean.doExport();
            } catch (Exception e) {
                throw new RuntimeException("Failed to export Jaws service: "
                        + serviceBean.getInterface().getName(), e);
            }
        }

        /* start heartbeat after all services are exported */
        try {
            JawsSwitcherUtils.setSwitcherValue(JawsConstants.REGISTRY_HEARTBEAT_SWITCHER, true);
        } catch (Exception e) {
            log.warn("[JawsBootstrap] failed to start heartbeat switcher", e);
        }

        log.info("[JawsBootstrap] all services exported, count={}", serviceBeans.size());
    }

    private void onContextClosed(ContextClosedEvent event) {
        if (stopped) {
            return;
        }
        stopped = true;

        Map<String, ServiceBean> serviceBeans =
                event.getApplicationContext().getBeansOfType(ServiceBean.class);
        for (ServiceBean serviceBean : serviceBeans.values()) {
            try {
                serviceBean.destroy();
            } catch (Exception e) {
                log.warn("[JawsBootstrap] failed to destroy ServiceBean: {}",
                        serviceBean.getServiceBeanName(), e);
            }
        }

        /* close heartbeat switcher */
        try {
            JawsSwitcherUtils.setSwitcherValue(JawsConstants.REGISTRY_HEARTBEAT_SWITCHER, false);
        } catch (Exception e) {
            log.debug("[JawsBootstrap] failed to close heartbeat switcher", e);
        }

        log.info("[JawsBootstrap] shutdown complete");
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}
