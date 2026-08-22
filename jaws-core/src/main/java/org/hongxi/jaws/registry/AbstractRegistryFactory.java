package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.config.configcenter.DynamicConfiguration;
import org.hongxi.jaws.config.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.exception.JawsFrameworkException;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Create and cache registry.
 * <p>
 * Created by shenhongxi on 2021/4/21.
 */

public abstract class AbstractRegistryFactory implements RegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    private static final ReentrantLock lock = new ReentrantLock();
    private static final ConcurrentHashMap<String, Registry> registries = new ConcurrentHashMap<>();

    /**
     * Whether a remote DynamicConfiguration has been initialized.
     * <p>
     * Convention over configuration: with multiple (possibly heterogeneous)
     * registries, the FIRST registry in configuration order acts as the
     * dynamic configuration center; later registries skip initialization to
     * avoid overwriting the active config center and leaking connections.
     * If the first registry has no matching DynamicConfiguration extension,
     * the next one in order takes over. Guarded by {@link #lock}.
     */
    private static volatile boolean dynamicConfigurationInitialized;

    protected String getRegistryUri(URL url) {
        return url.getUri();
    }

    @Override
    public Registry getRegistry(URL url) {
        String registryUri = getRegistryUri(url);
        try {
            lock.lock();
            Registry registry = registries.get(registryUri);
            if (registry != null) {
                return registry;
            }
            registry = createRegistry(url);
            if (registry == null) {
                throw new JawsFrameworkException("Create registry false for url:" + url);
            }
            registries.put(registryUri, registry);
            initDynamicConfiguration(url);
            return registry;
        } catch (Exception e) {
            throw new JawsFrameworkException("Create registry false for url:" + url, e);
        } finally {
            lock.unlock();
        }
    }

    protected abstract Registry createRegistry(URL url);

    /**
     * Try to load and initialize a remote DynamicConfiguration matching the registry type.
     * <p>
     * Uses the URL protocol (e.g., "nacos", "zookeeper") as the SPI extension name
     * to find the corresponding DynamicConfiguration implementation.
     */
    private void initDynamicConfiguration(URL url) {
        if (dynamicConfigurationInitialized) {
            log.info("DynamicConfiguration already initialized, skip registry type: {}", url.getProtocol());
            return;
        }
        try {
            String registryType = url.getProtocol();
            DynamicConfiguration dc = ExtensionLoader.getExtensionLoader(DynamicConfiguration.class)
                    .getExtension(registryType);
            if (dc != null) {
                dc.init(url);
                DynamicConfigurationUtils.setDynamicConfiguration(dc);
                dynamicConfigurationInitialized = true;
                log.info("DynamicConfiguration initialized with registry type: {}", registryType);
            }
        } catch (Exception e) {
            log.warn("Failed to init DynamicConfiguration for registry type: {}, msg: {}",
                    url.getProtocol(), e.getMessage());
        }
    }
}