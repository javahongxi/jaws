package org.hongxi.jaws.rest.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Jaws REST integration.
 * <p>
 * When enabled, all exported Jaws services will be exposed via REST API
 * for traditional HTTP clients.
 *
 * @author shenhongxi
 */
@ConfigurationProperties(JawsRestProperties.CONFIG_PREFIX)
public class JawsRestProperties {

    public static final String CONFIG_PREFIX = "jaws.rest";

    /** Whether to enable REST API for Jaws services */
    private boolean enabled = true;

    /** HTTP endpoint path prefix for REST API (default: /rest) */
    private String endpoint = "/rest";

    /**
     * Optional list of service interface names to expose.
     * If empty, all exported Jaws services are exposed.
     */
    private List<String> includeServices = new ArrayList<>();

    /**
     * Optional list of service interface names to exclude from REST exposure.
     */
    private List<String> excludeServices = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
