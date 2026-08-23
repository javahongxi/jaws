package org.hongxi.jaws.configcenter;

/**
 * Listener for dynamic configuration changes.
 * <p>
 * Created by shenhongxi on 2026/8/11.
 */
public interface ConfigurationListener {

    /**
     * Called when a configuration value changes.
     *
     * @param key      the configuration key
     * @param newValue the new value, null if the key was removed
     */
    void onConfigChanged(String key, String newValue);
}
