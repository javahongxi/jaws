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
 *   <li>Nacos serviceName = "jaws/{path}" (e.g. jaws/org.hongxi.jaws.sample.api.DemoService),
 *       or the bare {@code path} when the URL carries an explicitly empty
 *       {@code registryNamespace} — which is what it takes to reach a service that
 *       some non-Jaws process registered under its own name</li>
 *   <li>Nacos instance metadata carries URL parameters (protocol, path, etc.)</li>
 * </ul>
 * <p>
 * Created by shenhongxi on 2026/7/17.
 */
public class NacosPathUtils {

    /**
     * Build the Nacos service name from Jaws URL.
     * Format: "jaws/{path}", or just "{path}" when the namespace is empty.
     */
    public static String toServiceName(URL url) {
        String namespace = url.getRegistryNamespace();
        if (namespace.isEmpty()) {
            // A non-Jaws provider registered this name verbatim; adding our own
            // namespace would look up a service that does not exist.
            return url.getPath();
        }
        return namespace + JawsConstants.PATH_SEPARATOR + url.getPath();
    }

    /**
     * Build the Nacos group name from Jaws URL.
     */
    public static String toGroup(URL url) {
        return url.getGroup();
    }
}
