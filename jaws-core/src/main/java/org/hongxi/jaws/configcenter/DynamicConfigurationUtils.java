package org.hongxi.jaws.configcenter;

import java.util.function.IntPredicate;

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

    // ---- fallback-chain resolvers ----

    /**
     * Resolve an int config by trying the given keys in order (most specific
     * first). The first configured value wins; otherwise {@code defaultValue}
     * is returned.
     */
    public static int resolveIntConfig(int defaultValue, String... keys) {
        return resolveIntConfig(defaultValue, v -> true, keys);
    }

    /**
     * Resolve an int config by trying the given keys in order (most specific
     * first). The first configured value accepted by {@code valid} wins;
     * otherwise {@code defaultValue} is returned.
     */
    public static int resolveIntConfig(int defaultValue, IntPredicate valid, String... keys) {
        DynamicConfiguration dc = getDynamicConfiguration();
        if (!dc.hasAnyConfig()) {
            return defaultValue;
        }
        for (String key : keys) {
            int val = dc.getIntConfig(key, Integer.MIN_VALUE);
            if (val != Integer.MIN_VALUE && valid.test(val)) {
                return val;
            }
        }
        return defaultValue;
    }

    /**
     * Resolve a String config by trying the given keys in order (most specific
     * first). The first non-empty value wins; otherwise null is returned.
     */
    public static String resolveStringConfig(String... keys) {
        DynamicConfiguration dc = getDynamicConfiguration();
        if (!dc.hasAnyConfig()) {
            return null;
        }
        for (String key : keys) {
            String val = dc.getConfig(key);
            if (val != null && !val.isEmpty()) {
                return val;
            }
        }
        return null;
    }
}
