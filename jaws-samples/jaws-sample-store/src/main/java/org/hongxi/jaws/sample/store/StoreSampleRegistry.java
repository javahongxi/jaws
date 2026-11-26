package org.hongxi.jaws.sample.store;

import org.hongxi.jaws.config.RegistryConfig;

import java.net.URI;

/**
 * Parses the shared {@code -Dregistry} sample property (e.g.
 * {@code harbor://127.0.0.1:19848}) into a RegistryConfig, so the broker,
 * producer and consumer mains all speak the same registry spec format.
 */
final class StoreSampleRegistry {

    private StoreSampleRegistry() {
    }

    static RegistryConfig parse(String spec) {
        URI uri = URI.create(spec);
        RegistryConfig registry = new RegistryConfig();
        registry.setProtocol(uri.getScheme());
        registry.setId("storeRegistry");
        registry.setAddress(uri.getHost() + ":" + uri.getPort());
        registry.setPort(uri.getPort());
        return registry;
    }
}