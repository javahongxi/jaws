package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

/**
 * Listener for dynamic service configuration changes.
 * <p>
 * When the config node in registry changes, the raw config string
 * (JSON format) is delivered to the listener.
 * <p>
 * Created by shenhongxi on 2026/7/19.
 */
public interface ConfigListener {

    /**
     * Called when the service configuration changes.
     *
     * @param serviceUrl   the reference service URL this listener is bound to
     * @param configString the new config string (JSON), or null/empty if config is removed
     */
    void notifyConfig(URL serviceUrl, String configString);
}
