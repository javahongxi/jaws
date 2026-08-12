package org.hongxi.jaws.cluster.support;

import org.hongxi.jaws.cluster.Router;
import org.hongxi.jaws.config.configcenter.ConfigurationListener;
import org.hongxi.jaws.config.configcenter.DynamicConfiguration;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationKeys;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@link Router} that reads routing rules from {@link DynamicConfiguration} and
 * applies them to filter candidate references on every RPC call.
 * <p>
 * Route rule format (query-string style):
 * <pre>
 *   host=192.168.1.*        -- match provider host by glob pattern (* = any)
 *   group=groupA,groupB      -- only route to providers in these groups
 *   enabled=false            -- disable this router entirely
 * </pre>
 * <p>
 * Resolution order: service-level key -> global key.
 * Changes take effect immediately via {@link ConfigurationListener}.
 *
 * @param <T> service type
 */
public class DynamicConfigRouter<T> implements Router<T>, ConfigurationListener {

    private static final Logger log = LoggerFactory.getLogger(DynamicConfigRouter.class);

    private final String interfaceName;

    private volatile RouteRule currentRule;

    public DynamicConfigRouter(String interfaceName) {
        this.interfaceName = interfaceName;
        // Load initial rule
        String ruleStr = loadRuleString();
        this.currentRule = parseRule(ruleStr);

        // Register listeners for dynamic updates
        DynamicConfiguration dc = DynamicConfigurationUtils.getDynamicConfiguration();
        dc.addListener(DynamicConfigurationKeys.routeRule(interfaceName), this);
        dc.addListener(DynamicConfigurationKeys.GLOBAL_ROUTE_RULE, this);
    }

    @Override
    public List<Reference<T>> route(List<Reference<T>> references, Request request) {
        RouteRule rule = this.currentRule;
        if (rule == null || !rule.enabled) {
            return references;
        }
        List<Reference<T>> filtered = new ArrayList<>();
        for (Reference<T> ref : references) {
            if (matches(ref, rule)) {
                filtered.add(ref);
            }
        }
        return filtered.isEmpty() ? references : filtered;
    }

    @Override
    public void onConfigChanged(String key, String newValue) {
        String ruleStr = loadRuleString();
        RouteRule newRule = parseRule(ruleStr);
        RouteRule oldRule = this.currentRule;
        this.currentRule = newRule;
        log.info("DynamicConfigRouter rule updated for {}: old=[{}], new=[{}]",
                interfaceName, oldRule, newRule);
    }

    /**
     * Remove listeners to avoid memory leaks when the cluster is destroyed.
     */
    public void destroy() {
        DynamicConfiguration dc = DynamicConfigurationUtils.getDynamicConfiguration();
        dc.removeListener(DynamicConfigurationKeys.routeRule(interfaceName), this);
        dc.removeListener(DynamicConfigurationKeys.GLOBAL_ROUTE_RULE, this);
    }

    // ---- internal ----

    private String loadRuleString() {
        DynamicConfiguration dc = DynamicConfigurationUtils.getDynamicConfiguration();
        // service-level takes priority
        String val = dc.getConfig(DynamicConfigurationKeys.routeRule(interfaceName));
        if (val != null && !val.isEmpty()) {
            return val;
        }
        return dc.getConfig(DynamicConfigurationKeys.GLOBAL_ROUTE_RULE);
    }

    private boolean matches(Reference<T> ref, RouteRule rule) {
        URL serviceUrl = ref.getServiceUrl();
        if (rule.hostPattern != null) {
            String host = serviceUrl.getHost();
            if (host == null || !rule.hostPattern.matcher(host).matches()) {
                return false;
            }
        }
        if (rule.groups != null && !rule.groups.isEmpty()) {
            String group = serviceUrl.getGroup();
            if (!rule.groups.contains(group)) {
                return false;
            }
        }
        return true;
    }

    private static RouteRule parseRule(String ruleStr) {
        if (ruleStr == null || ruleStr.isEmpty()) {
            return null;
        }
        RouteRule rule = new RouteRule();
        String[] pairs = ruleStr.split("&");
        for (String pair : pairs) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            switch (key) {
                case "enabled":
                    rule.enabled = !"false".equalsIgnoreCase(value);
                    break;
                case "host":
                    // Convert glob pattern to regex: * -> .*
                    String regex = value.replace(".", "\\.").replace("*", ".*");
                    rule.hostPattern = Pattern.compile(regex);
                    break;
                case "group":
                    rule.groups = new ArrayList<>();
                    for (String g : value.split(",")) {
                        g = g.trim();
                        if (!g.isEmpty()) {
                            rule.groups.add(g);
                        }
                    }
                    break;
                default:
                    log.warn("DynamicConfigRouter unknown rule key: {}", key);
            }
        }
        return rule;
    }

    private static class RouteRule {
        boolean enabled = true;
        Pattern hostPattern;
        List<String> groups;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("RouteRule{enabled=").append(enabled);
            if (hostPattern != null) {
                sb.append(", host=").append(hostPattern.pattern());
            }
            if (groups != null) {
                sb.append(", groups=").append(groups);
            }
            sb.append('}');
            return sb.toString();
        }
    }
}
