package org.hongxi.jaws.registry.nacos;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.rpc.URL;

/**
 * Utility class for building Nacos service names and group names from Jaws URL.
 * <p>
 * Nacos uses a flat service model instead of ZooKeeper's tree structure.
 * The mapping is:
 * <ul>
 *   <li>Nacos group = Jaws group</li>
 *   <li>Nacos serviceName = "jaws/{path}" (e.g. jaws/org.hongxi.jaws.sample.api.DemoService)</li>
 *   <li>Nacos instance metadata carries the full URL string and node type</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class NacosPathUtils {

    /**
     * Nacos Config group prefix for JAWS framework.
     * Follows Nacos group naming best practice: uppercase with framework prefix.
     */
    private static final String NACOS_CONFIG_GROUP_PREFIX = "JAWS_";

    /**
     * Build the Nacos service name from Jaws URL.
     * Format: "jaws/{path}"
     */
    public static String toServiceName(URL url) {
        return JawsConstants.NACOS_REGISTRY_NAMESPACE + JawsConstants.PATH_SEPARATOR + url.getPath();
    }

    /**
     * Build the Nacos group name from Jaws URL.
     */
    public static String toGroup(URL url) {
        return url.getGroup();
    }

    /**
     * Build the Nacos Config dataId for command storage.
     * Command is at group level (not per-interface), aligned with ZK's /jaws/{group}/command.
     * Uses dot instead of slash since Nacos Config dataId does not support '/'.
     */
    public static String toCommandDataId(URL url) {
        return "jaws.command";
    }

    /**
     * Build the Nacos Config group for command storage.
     * Format: "JAWS_" + uppercase service group (e.g. JAWS_DEFAULT_RPC).
     */
    public static String toCommandGroup(URL url) {
        return toNacosConfigGroup(url.getGroup());
    }

    /**
     * Build the Nacos Config dataId for dynamic configuration storage.
     * dataId = interface fully qualified name (e.g. org.hongxi.jaws.sample.api.DemoService)
     */
    public static String toConfigDataId(URL url) {
        return url.getPath();
    }

    /**
     * Build the Nacos Config group for dynamic configuration.
     * Format: "JAWS_" + uppercase service group (e.g. JAWS_DEFAULT_RPC).
     */
    public static String toConfigGroup(URL url) {
        return toNacosConfigGroup(url.getGroup());
    }

    /**
     * Build the instance identifier (host:port) from Jaws URL.
     */
    public static String toInstanceId(URL url) {
        return url.getServerPortStr();
    }

    /**
     * Convert Jaws service group to Nacos Config group name.
     * Follows Nacos group naming best practice: uppercase with framework prefix.
     */
    private static String toNacosConfigGroup(String group) {
        return NACOS_CONFIG_GROUP_PREFIX + group.toUpperCase();
    }
}
