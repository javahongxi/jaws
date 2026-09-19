package org.hongxi.jaws.wire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A registry of {@link LoadBalancerProvider}s keyed by policy name, mirroring
 * grpc-java's {@code LoadBalancerRegistry}. {@link ManagedChannel.Builder} looks
 * up the load balancer by name here, so a new policy can be added either as a
 * {@link java.util.ServiceLoader} service (drop a file under
 * {@code META-INF/services}) or programmatically via {@link #register}.
 *
 * @author shenhongxi
 */
public final class LoadBalancerRegistry {
    private static final Logger log = LoggerFactory.getLogger(LoadBalancerRegistry.class);

    /** Default policy name when a channel does not choose one. */
    public static final String DEFAULT_POLICY = "round_robin";

    private static final LoadBalancerRegistry INSTANCE = new LoadBalancerRegistry();

    private final Map<String, LoadBalancerProvider> providers = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private LoadBalancerRegistry() {
        // Built-ins registered eagerly so they are always present, even without
        // a services file on the classpath (e.g. during unit tests).
        register(new RoundRobinLoadBalancer.Provider());
        register(new PickFirstLoadBalancer.Provider());
    }

    public static LoadBalancerRegistry getDefault() {
        return INSTANCE;
    }

    /** Register (or replace) a provider, highest priority for its name wins. */
    public void register(LoadBalancerProvider provider) {
        LoadBalancerProvider existing = providers.get(provider.getName());
        if (existing == null || provider.getPriority() >= existing.getPriority()) {
            providers.put(provider.getName(), provider);
        }
    }

    public void deregister(LoadBalancerProvider provider) {
        providers.remove(provider.getName(), provider);
    }

    /**
     * @param name the policy name
     * @return the registered provider, or {@code null} if none is known
     */
    public LoadBalancerProvider getProvider(String name) {
        ensureServiceProvidersLoaded();
        return providers.get(name);
    }

    /**
     * Create a fresh {@link LoadBalancer} by policy name.
     *
     * @param name the policy name; must be registered
     * @throws IllegalArgumentException if no provider is known for {@code name}
     */
    public LoadBalancer newLoadBalancer(String name) {
        LoadBalancerProvider provider = getProvider(name);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "No LoadBalancer registered for policy '" + name + "'; known: "
                            + providers.keySet());
        }
        return provider.newLoadBalancer();
    }

    /** Lazy, once-only ServiceLoader discovery of additional providers. */
    private void ensureServiceProvidersLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                for (LoadBalancerProvider provider :
                        ServiceLoader.load(LoadBalancerProvider.class)) {
                    register(provider);
                }
            } catch (Throwable t) {
                log.warn("Failed to load ServiceLoader LoadBalancerProviders: {}",
                        t.getMessage());
            }
            loaded = true;
        }
    }
}
