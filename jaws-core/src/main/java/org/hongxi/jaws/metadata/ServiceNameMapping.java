package org.hongxi.jaws.metadata;

import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;

/**
 * Interface-to-application mapping stored in the config center, the minimal
 * counterpart of Dubbo's {@code ServiceNameMapping} (which keeps the mapping in
 * the metadata report with group=mapping and CAS writes).
 * <p>
 * The provider writes one entry per exported interface when registering in
 * application-level mode; the consumer reads it at reference initialization to
 * decide which application to subscribe to. A present mapping routes the
 * reference to application-level discovery, an absent one keeps the classic
 * interface-level path — the mapping itself is the switch, consumers need no
 * extra configuration.
 * <p>
 * Storage rides the existing {@link org.hongxi.jaws.configcenter.DynamicConfiguration}
 * SPI, so all three registry legs (Nacos / ZooKeeper / Harbor) carry the mapping
 * with no extra code. Boundaries of this minimal slice: the value is a single
 * application name (last writer wins, no CAS), and entries are never removed —
 * like Dubbo, unexport leaves the mapping behind.
 */
public final class ServiceNameMapping {

    /** Key prefix separating mapping entries from dynamic-config toggles. */
    public static final String KEY_PREFIX = "mapping:";

    private ServiceNameMapping() {
    }

    public static String mappingKey(String interfaceName) {
        return KEY_PREFIX + interfaceName;
    }

    /**
     * @return the application providing the interface, or null when no mapping
     *         exists (consumer should fall back to interface-level discovery)
     */
    public static String getMapping(String interfaceName) {
        String application = DynamicConfigurationUtils.getConfig(mappingKey(interfaceName));
        return StringUtils.isBlank(application) ? null : application.trim();
    }

    /**
     * Publish (or overwrite) the mapping for one interface.
     */
    public static void setMapping(String interfaceName, String application) {
        DynamicConfigurationUtils.setConfig(mappingKey(interfaceName), application);
    }
}