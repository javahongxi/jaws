package org.hongxi.jaws.configcenter;

/**
 * Centralized dynamic configuration keys used by framework internals.
 * <p>
 * Each SPI extension point reads these keys from {@link DynamicConfiguration}
 * so that values can be changed at runtime without restarting the process.
 * <p>
 * Key naming convention:
 * <ul>
 *   <li>Global: {@code jaws.<feature>}</li>
 *   <li>Service-level: {@code jaws.<feature>.<interfaceName>}</li>
 *   <li>Method-level: {@code jaws.<feature>.<interfaceName>.<methodName>}</li>
 * </ul>
 * Service-level keys override global keys; method-level keys override service-level keys.
 *
 * @see DynamicConfiguration
 */
public final class DynamicConfigurationKeys {

    private DynamicConfigurationKeys() {
    }

    // ==================== Timeout / Retry ====================

    /** Global default request timeout in milliseconds */
    public static final String GLOBAL_REQUEST_TIMEOUT = "jaws.requestTimeout";

    /** Global default retry count for failover HA strategy */
    public static final String GLOBAL_RETRIES = "jaws.retries";

    /**
     * Build service-level request timeout key.
     *
     * @param interfaceName the service interface name
     * @return key like "jaws.requestTimeout.com.example.DemoService"
     */
    public static String requestTimeout(String interfaceName) {
        return "jaws.requestTimeout." + interfaceName;
    }

    /**
     * Build method-level request timeout key.
     *
     * @param interfaceName the service interface name
     * @param methodName    the method name
     * @return key like "jaws.requestTimeout.com.example.DemoService.sayHello"
     */
    public static String requestTimeout(String interfaceName, String methodName) {
        return "jaws.requestTimeout." + interfaceName + "." + methodName;
    }

    /**
     * Build service-level retries key.
     *
     * @param interfaceName the service interface name
     * @return key like "jaws.retries.com.example.DemoService"
     */
    public static String retries(String interfaceName) {
        return "jaws.retries." + interfaceName;
    }

    /**
     * Build method-level retries key.
     *
     * @param interfaceName the service interface name
     * @param methodName    the method name
     * @return key like "jaws.retries.com.example.DemoService.sayHello"
     */
    public static String retries(String interfaceName, String methodName) {
        return "jaws.retries." + interfaceName + "." + methodName;
    }

    // ==================== Filter Toggle ====================

    /**
     * Build the toggle key for a specific filter on a specific service.
     *
     * @param filterName the SPI filter name (e.g. "tokenAuth", "access")
     * @return key like "jaws.filter.tokenAuth.enabled"
     */
    public static String filterEnabled(String filterName) {
        return "jaws.filter." + filterName + ".enabled";
    }

    /**
     * Build the toggle key for a specific filter on a specific service.
     *
     * @param filterName    the SPI filter name
     * @param interfaceName the service interface name
     * @return key like "jaws.filter.tokenAuth.com.example.DemoService.enabled"
     */
    public static String filterEnabled(String filterName, String interfaceName) {
        return "jaws.filter." + filterName + "." + interfaceName + ".enabled";
    }

    // ==================== Dynamic Routing ====================

    /** Global dynamic routing rule (JSON or expression) */
    public static final String GLOBAL_ROUTE_RULE = "jaws.route.rule";

    /**
     * Build service-level routing rule key.
     *
     * @param interfaceName the service interface name
     * @return key like "jaws.route.rule.com.example.DemoService"
     */
    public static String routeRule(String interfaceName) {
        return "jaws.route.rule." + interfaceName;
    }

    /**
     * Build service-level routing rule weight key.
     *
     * @param interfaceName the service interface name
     * @return key like "jaws.route.weight.com.example.DemoService"
     */
    public static String routeWeight(String interfaceName) {
        return "jaws.route.weight." + interfaceName;
    }
}
