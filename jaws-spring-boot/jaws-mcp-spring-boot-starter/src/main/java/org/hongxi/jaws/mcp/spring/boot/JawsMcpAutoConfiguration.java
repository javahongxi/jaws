package org.hongxi.jaws.mcp.spring.boot;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import org.hongxi.jaws.mcp.JawsMcpServer;
import org.hongxi.jaws.mcp.JawsMcpToolSpec;
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
 * Auto-Configuration for exposing Jaws RPC services as MCP Tools.
 * <p>
 * This configuration:
 * <ol>
 *   <li>Creates the MCP transport provider and registers it as a servlet</li>
 *   <li>Creates the {@link McpSyncServer} (initially with no tools)</li>
 *   <li>On {@link ContextRefreshedEvent} (after Jaws services are exported),
 *       dynamically adds tools from all exported {@link ServiceBean}s</li>
 * </ol>
 * <p>
 * The MCP endpoint is available at the configured path (default: /mcp).
 *
 * @author shenhongxi
 * @see JawsMcpServer
 * @see JawsMcpProperties
 */
@AutoConfiguration(after = JawsAutoConfiguration.class)
@EnableConfigurationProperties(JawsMcpProperties.class)
@ConditionalOnProperty(prefix = JawsMcpProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class JawsMcpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JawsMcpAutoConfiguration.class);

    private final JawsMcpProperties properties;

    public JawsMcpAutoConfiguration(JawsMcpProperties properties) {
        this.properties = properties;
    }

    /**
     * Create the MCP transport provider.
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(properties.getEndpoint())
                .build();
    }

    /**
     * Register the MCP transport as a servlet.
     */
    @Bean
    public ServletRegistrationBean<HttpServlet> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transportProvider) {
        ServletRegistrationBean<HttpServlet> registration =
                new ServletRegistrationBean<>(transportProvider, properties.getEndpoint());
        registration.setName("jawsMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * Create the MCP sync server (initially with no tools).
     * Tools are added dynamically after Jaws services are exported.
     */
    @Bean
    @ConditionalOnMissingBean
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transportProvider) {
        return McpServer.sync(transportProvider)
                .serverInfo(properties.getServerName(), properties.getServerVersion())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();
    }

    /**
     * Listener that adds tools from exported Jaws services after context refresh.
     * Uses {@link Ordered#LOWEST_PRECEDENCE} to ensure it runs after
     * {@link org.hongxi.jaws.spring.boot.JawsBootstrap} which exports services.
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> jawsMcpToolRegistrar(
            ApplicationContext applicationContext,
            McpSyncServer mcpSyncServer) {
        return event -> {
            Map<String, ServiceBean> serviceBeans = applicationContext.getBeansOfType(ServiceBean.class);
            if (serviceBeans.isEmpty()) {
                log.warn("[JawsMcp] No ServiceBeans found, MCP server will have no tools");
                return;
            }

            Set<String> includeSet = new HashSet<>(properties.getIncludeServices());
            Set<String> excludeSet = new HashSet<>(properties.getExcludeServices());

            int toolCount = 0;

            for (ServiceBean serviceBean : serviceBeans.values()) {
                Class<?> interfaceClass = serviceBean.getInterface();
                String interfaceName = interfaceClass.getName();

                // Apply include/exclude filters
                if (!includeSet.isEmpty() && !includeSet.contains(interfaceName)) {
                    log.debug("[JawsMcp] Skipping service (not in include list): {}", interfaceName);
                    continue;
                }
                if (excludeSet.contains(interfaceName)) {
                    log.debug("[JawsMcp] Skipping service (in exclude list): {}", interfaceName);
                    continue;
                }

                // Get providers from exporters
                List<? extends Exporter<?>> exporters = serviceBean.getExporters();
                if (exporters.isEmpty()) {
                    log.warn("[JawsMcp] Service has no exporters: {}", interfaceName);
                    continue;
                }

                Provider<?> provider = exporters.get(0).getProvider();
                List<JawsMcpToolSpec> toolSpecs = JawsMcpServer.createToolSpecs(interfaceClass, provider);
                List<McpServerFeatures.SyncToolSpecification> syncSpecs =
                        JawsMcpServer.buildSyncToolSpecifications(toolSpecs);

                for (McpServerFeatures.SyncToolSpecification spec : syncSpecs) {
                    mcpSyncServer.addTool(spec);
                    toolCount++;
                }
            }

            if (toolCount > 0) {
                mcpSyncServer.notifyToolsListChanged();
                log.info("[JawsMcp] Dynamically registered {} MCP tools from {} services",
                        toolCount, serviceBeans.size());
            } else {
                log.warn("[JawsMcp] No tools registered for MCP server");
            }
        };
    }
}
