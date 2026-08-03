package org.hongxi.jaws.mcp.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Jaws MCP integration.
 * <p>
 * When enabled, all exported Jaws services will be exposed as MCP Tools
 * through an HTTP+SSE endpoint.
 *
 * @author shenhongxi
 */
@ConfigurationProperties(JawsMcpProperties.CONFIG_PREFIX)
public class JawsMcpProperties {

    public static final String CONFIG_PREFIX = "jaws.mcp";

    /** Whether to enable MCP server for Jaws services */
    private boolean enabled = true;

    /** MCP server name advertised to clients */
    private String serverName = "jaws-mcp-server";

    /** MCP server version */
    private String serverVersion = "1.0.0";

    /** HTTP endpoint path for MCP protocol (default: /mcp) */
    private String endpoint = "/mcp";

    /**
     * Optional list of service interface names to expose.
     * If empty, all exported Jaws services are exposed.
     */
    private List<String> includeServices = new ArrayList<>();

    /**
     * Optional list of service interface names to exclude from MCP exposure.
     */
    private List<String> excludeServices = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public List<String> getIncludeServices() {
        return includeServices;
    }

    public void setIncludeServices(List<String> includeServices) {
        this.includeServices = includeServices;
    }

    public List<String> getExcludeServices() {
        return excludeServices;
    }

    public void setExcludeServices(List<String> excludeServices) {
        this.excludeServices = excludeServices;
    }
}
