package org.hongxi.jaws.sample.mcp.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI MCP consumer — verifies the jaws provider's MCP endpoint
 * ({@code POST /mcp}) using the modern (2026-07-28 stateless) protocol
 * supported since Spring AI 2.1.0.
 * <p>
 * No LLM involved: the auto-configured {@link ToolCallbackProvider} lists
 * the tools auto-registered from the jaws {@code DemoService} interface and
 * calls them directly, then the application exits.
 *
 * <pre>
 * Demo scenario:
 * 1. Start jaws-sample-http-provider first (MCP endpoint on port 10000)
 * 2. Run this application:
 *    mvn spring-boot:run -pl jaws-samples/jaws-sample-mcp-consumer
 * 3. It lists the discovered tools and calls "hello" and "getUsers"
 * </pre>
 *
 * <p>The connection is configured with {@code protocol=MODERN} in
 * application.yml; switch {@code spring.ai.mcp.client.type} to ASYNC to
 * exercise the async client type added in Spring AI 2.1.0.
 *
 * @author shenhongxi
 */
@SpringBootApplication
public class McpConsumerApplication {
    private static final Logger log = LoggerFactory.getLogger(McpConsumerApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(McpConsumerApplication.class, args);
        // One-shot verification: exit explicitly so HTTP client threads don't keep the JVM alive
        System.exit(SpringApplication.exit(context));
    }

    @Bean
    public ApplicationRunner mcpVerifier(ToolCallbackProvider toolCallbackProvider) {
        return args -> {
            ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
            if (callbacks.length == 0) {
                log.warn("No MCP tools discovered — is jaws-sample-http-provider running on port 10000?");
                return;
            }

            log.info("Discovered {} MCP tool(s) from jaws provider:", callbacks.length);
            for (ToolCallback callback : callbacks) {
                log.info("  {} — {}", callback.getToolDefinition().name(), callback.getToolDefinition().description());
            }

            callTool(callbacks, "hello", "{\"name\":\"lily\"}");
            callTool(callbacks, "getUsers", "{}");
        };
    }

    private static void callTool(ToolCallback[] callbacks, String toolName, String arguments) {
        ToolCallback callback = findTool(callbacks, toolName);
        if (callback == null) {
            log.warn("Tool not found: {}", toolName);
            return;
        }
        String result = callback.call(arguments);
        log.info("tools/call {}({}) -> {}", callback.getToolDefinition().name(), arguments, result);
    }

    /**
     * Match by bare tool name or the default {@code <connection>_<tool>} prefix
     * applied by Spring AI's modern tool name prefix generator.
     */
    private static ToolCallback findTool(ToolCallback[] callbacks, String toolName) {
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (name.equals(toolName) || name.endsWith("_" + toolName)) {
                return callback;
            }
        }
        return null;
    }
}