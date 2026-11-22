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
 * application name (last writer wins, no CAS), and unexport never removes the
 * entry automatically — like Dubbo, the mapping outlives the provider. Callers
 * that want the interface-level fallback back (samples switching discovery
 * modes, teardown scripts) remove it explicitly via {@link #removeMapping}.
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

    /**
     * Remove the mapping for one interface, restoring the consumer's
     * interface-level fallback. Never called by unexport — the mapping is
     * persistent by design; this is the explicit opt-out for callers that
     * own the lifecycle (e.g. sample providers cleaning up on shutdown).
     *
     * @return true when an entry was actually removed
     */
    public static boolean removeMapping(String interfaceName) {
        return DynamicConfigurationUtils.removeConfig(mappingKey(interfaceName));
    }
}