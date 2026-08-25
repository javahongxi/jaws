package org.hongxi.jaws.sample.mcp;

import org.hongxi.jaws.spring.boot.annotation.EnableJaws;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Provider sample application.
 * <p>
 * Starts a Jaws RPC provider with MCP server enabled.
 * All exported Jaws services are automatically exposed as MCP Tools
 * at the configured endpoint (default: /mcp).
 * <p>
 * Usage:
 * <ol>
 *   <li>Run this application</li>
 *   <li>MCP endpoint available at http://localhost:8082/mcp</li>
 *   <li>Connect with any MCP client to discover and invoke tools</li>
 * </ol>
 */
@EnableJaws
@SpringBootApplication
public class McpProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpProviderApplication.class, args);
    }
}
