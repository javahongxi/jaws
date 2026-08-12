package org.hongxi.jaws.config.configcenter;

/**
 * Static utility for accessing {@link DynamicConfiguration}.
 * <p>
 * Provides a global access point to the current DynamicConfiguration instance.
 * By default uses {@link LocalDynamicConfiguration}. The implementation is
 * replaced by the registry module when a remote config center is available
 * (e.g., NacosDynamicConfiguration, ZookeeperDynamicConfiguration).
 * <p>
 * Created by shenhongxi on 2026/8/11.
 */
public class DynamicConfigurationUtils {

    private static volatile DynamicConfiguration dynamicConfiguration = new LocalDynamicConfiguration();

    /**
     * Get the current DynamicConfiguration instance.
     */
    public static DynamicConfiguration getDynamicConfiguration() {
        return dynamicConfiguration;
    }

    /**
     * Replace the DynamicConfiguration instance (e.g., with a remote implementation).
     */
    public static void setDynamicConfiguration(DynamicConfiguration configuration) {
        dynamicConfiguration = configuration;
    }

    // ---- convenience delegate methods ----

    public static String getConfig(String key) {
        return dynamicConfiguration.getConfig(key);
    }

    public static String getConfig(String key, String defaultValue) {
        return dynamicConfiguration.getConfig(key, defaultValue);
    }

    public static void setConfig(String key, String value) {
        dynamicConfiguration.setConfig(key, value);
    }

    public static boolean isEnabled(String key) {
        return dynamicConfiguration.isEnabled(key);
    }

    public static boolean isEnabled(String key, boolean defaultValue) {
        return dynamicConfiguration.isEnabled(key, defaultValue);
    }

    public static void setEnabled(String key, boolean value) {
        dynamicConfiguration.setEnabled(key, value);
    }

    public static void addListener(String key, ConfigurationListener listener) {
        dynamicConfiguration.addListener(key, listener);
    }

    public static void removeListener(String key, ConfigurationListener listener) {
        dynamicConfiguration.removeListener(key, listener);
    }
}
