package org.hongxi.jaws.config.configcenter;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

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
 * When a remote config center (e.g., Nacos) is available, a remote implementation
 * can be loaded via SPI to enable distributed config push.
 * <p>
 * Created by shenhongxi on 2026/8/11.
 *
 * @see LocalDynamicConfiguration
 */
@Spi(scope = Scope.SINGLETON)
public interface DynamicConfiguration {

    /**
     * Get configuration value by key.
     *
     * @param key the configuration key
     * @return the value, or null if not exists
     */
    String getConfig(String key);

    /**
     * Get configuration value by key with a default.
     *
     * @param key          the configuration key
     * @param defaultValue default value if key not exists
     * @return the value, or defaultValue if not exists
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
     * @param defaultValue default value if key not exists
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
