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
     * Build the Nacos service name for command storage.
     * Commands are stored as a special Nacos service with a single instance.
     */
    public static String toCommandServiceName(URL url) {
        return JawsConstants.NACOS_REGISTRY_NAMESPACE + JawsConstants.PATH_SEPARATOR + url.getPath() + JawsConstants.ZOOKEEPER_REGISTRY_COMMAND;
    }

    /**
     * Build the instance identifier (host:port) from Jaws URL.
     */
    public static String toInstanceId(URL url) {
        return url.getServerPortStr();
    }
}
