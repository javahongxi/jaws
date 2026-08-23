package org.hongxi.jaws.configcenter;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

/**
 * Dynamic configuration abstraction for runtime feature toggles and config management.
 * <p>
 * Supports:
 * <ul>
 *   <li>Getting/setting string configuration values by key</li>
 *   <li>Registering listeners for config change notifications</li>
 *   <li>Boolean toggle operations (convenience methods for feature flags)</li>
 * </ul>
 * <p>
 * Default implementation is {@link LocalDynamicConfiguration} (in-memory).
 * When a remote config center (e.g., Nacos) is available, the registry module
 * automatically initializes and installs a remote implementation via
 * {@link #init(URL)} during registry creation.
 * <p>
 * Created by shenhongxi on 2026/8/11.
 *
 * @see LocalDynamicConfiguration
 */
@Spi(singleton = true)
public interface DynamicConfiguration {

    /**
     * Initialize this configuration with a registry URL.
     * <p>
     * Called by the registry module after creating the registry, allowing
     * remote implementations (e.g., Nacos, Zookeeper) to establish their
     * connections using the same cluster info.
     * <p>
     * Default implementation is a no-op (for local/in-memory configs).
     *
     * @param registryUrl the registry URL containing connection info
     */
    default void init(URL registryUrl) {
    }

    /**
     * Check if any configuration entries exist.
     * <p>
     * This is a fast-path check for hot paths to avoid expensive key construction
     * and lookup when no dynamic configuration has been set. Remote implementations
     * should return {@code true} to always perform lookups.
     *
     * @return true if at least one configuration entry exists
     */
    default boolean hasAnyConfig() {
        return true;
    }

    /**
     * Get configuration value by key.
     *
     * @param key the configuration key
     * @return the value, or null if the key does not exist
     */
    String getConfig(String key);

    /**
     * Get configuration value by key with a default.
     *
     * @param key          the configuration key
     * @param defaultValue default value if the key does not exist
     * @return the value, or defaultValue if the key does not exist
     */
    default String getConfig(String key, String defaultValue) {
        String value = getConfig(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Set configuration value.
     *
     * @param key   the configuration key
     * @param value the value to set
     */
    void setConfig(String key, String value);

    /**
     * Get integer configuration value by key with a default.
     *
     * @param key          the configuration key
     * @param defaultValue default value if the key does not exist or parsing fails
     * @return the parsed int value, or defaultValue
     */
    default int getIntConfig(String key, int defaultValue) {
        String value = getConfig(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get long configuration value by key with a default.
     *
     * @param key          the configuration key
     * @param defaultValue default value if the key does not exist or parsing fails
     * @return the parsed long value, or defaultValue
     */
    default long getLongConfig(String key, long defaultValue) {
        String value = getConfig(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Check if a boolean toggle is enabled.
     * <p>
     * This is a convenience method equivalent to {@code "true".equals(getConfig(key))}.
     *
     * @param key the toggle key
     * @return true if the value is "true" (case-insensitive), false otherwise
     */
    default boolean isEnabled(String key) {
        return "true".equalsIgnoreCase(getConfig(key));
    }

    /**
     * Check if a boolean toggle is enabled, with a default value.
     *
     * @param key          the toggle key
     * @param defaultValue default value if the key does not exist
     * @return true if enabled, false if disabled, or defaultValue if not set
     */
    default boolean isEnabled(String key, boolean defaultValue) {
        String value = getConfig(key);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value);
    }

    /**
     * Set a boolean toggle value.
     *
     * @param key   the toggle key
     * @param value true to enable, false to disable
     */
    default void setEnabled(String key, boolean value) {
        setConfig(key, String.valueOf(value));
    }

    /**
     * Register a listener for configuration changes on a specific key.
     *
     * @param key      the configuration key to watch
     * @param listener the listener to notify on change
     */
    void addListener(String key, ConfigurationListener listener);

    /**
     * Remove a listener for configuration changes.
     *
     * @param key      the configuration key
     * @param listener the listener to remove
     */
    void removeListener(String key, ConfigurationListener listener);
}
