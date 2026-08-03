package org.hongxi.jaws.rest.spring.boot;

import jakarta.servlet.http.HttpServlet;
import org.hongxi.jaws.rest.RestInvokeServlet;
import org.hongxi.jaws.rpc.Exporter;
import org.hongxi.jaws.rpc.Provider;
import org.hongxi.jaws.spring.boot.JawsAutoConfiguration;
import org.hongxi.jaws.spring.boot.ServiceBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-Configuration for exposing Jaws RPC services via REST API.
 * <p>
 * This configuration:
 * <ol>
 *   <li>Creates the {@link RestInvokeServlet} and registers it as a servlet</li>
 *   <li>On {@link ContextRefreshedEvent} (after Jaws services are exported),
 *       dynamically registers services from all exported {@link ServiceBean}s</li>
 * </ol>
 * <p>
 * The REST endpoint is available at the configured path (default: /rest).
 * <p>
 * Endpoints:
 * <ul>
 *   <li>GET /rest/services - List all registered services</li>
 *   <li>GET /rest/services/{interfaceName} - Get methods for a specific service</li>
 *   <li>POST /rest/invoke/{interfaceName}/{methodName} - Invoke a service method</li>
 * </ul>
 *
 * @author shenhongxi
 * @see RestInvokeServlet
 * @see JawsRestProperties
 */
@AutoConfiguration(after = JawsAutoConfiguration.class)
@EnableConfigurationProperties(JawsRestProperties.class)
@ConditionalOnProperty(prefix = JawsRestProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class JawsRestAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JawsRestAutoConfiguration.class);

    private final JawsRestProperties properties;

    public JawsRestAutoConfiguration(JawsRestProperties properties) {
        this.properties = properties;
    }

    /**
     * Create the REST invoke servlet.
     */
    @Bean
    @ConditionalOnMissingBean
    public RestInvokeServlet restInvokeServlet() {
        return new RestInvokeServlet();
    }

    /**
     * Register the REST invoke servlet.
     */
    @Bean
    public ServletRegistrationBean<HttpServlet> restServletRegistration(RestInvokeServlet servlet) {
        ServletRegistrationBean<HttpServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getEndpoint() + "/*");
        registration.setName("jawsRestServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * Listener that registers services from exported Jaws services after context refresh.
     * Uses {@link Ordered#LOWEST_PRECEDENCE} to ensure it runs after
     * {@link org.hongxi.jaws.spring.boot.JawsBootstrap} which exports services.
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> jawsRestServiceRegistrar(
            ApplicationContext applicationContext,
            RestInvokeServlet restInvokeServlet) {
        return event -> {
            Map<String, ServiceBean> serviceBeans = applicationContext.getBeansOfType(ServiceBean.class);
            if (serviceBeans.isEmpty()) {
                log.warn("[JawsRest] No ServiceBeans found, REST API will have no services");
                return;
            }

            Set<String> includeSet = new HashSet<>(properties.getIncludeServices());
            Set<String> excludeSet = new HashSet<>(properties.getExcludeServices());

            int serviceCount = 0;

            for (ServiceBean serviceBean : serviceBeans.values()) {
                Class<?> interfaceClass = serviceBean.getInterface();
                String interfaceName = interfaceClass.getName();

                // Apply include/exclude filters
                if (!includeSet.isEmpty() && !includeSet.contains(interfaceName)) {
                    log.debug("[JawsRest] Skipping service (not in include list): {}", interfaceName);
                    continue;
                }
                if (excludeSet.contains(interfaceName)) {
                    log.debug("[JawsRest] Skipping service (in exclude list): {}", interfaceName);
                    continue;
                }

                // Get providers from exporters
                List<? extends Exporter<?>> exporters = serviceBean.getExporters();
                if (exporters.isEmpty()) {
                    log.warn("[JawsRest] Service has no exporters: {}", interfaceName);
                    continue;
                }

                Provider<?> provider = exporters.get(0).getProvider();
                restInvokeServlet.registerService(interfaceClass, provider);
                serviceCount++;
            }

            if (serviceCount > 0) {
                log.info("[JawsRest] Dynamically registered {} services for REST API", serviceCount);
            } else {
                log.warn("[JawsRest] No services registered for REST API");
            }
        };
    }
}
