package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

/**
 * Service registration facet of the registry: providers call
 * {@link #register(URL)} to publish themselves and {@link #unregister(URL)}
 * to withdraw.
 * <p>
 * Combined with {@link DiscoveryService}, this forms the full registry
 * contract aggregated by {@link Registry}.
 *
 * @see Registry
 * @see DiscoveryService
 *
 * Created by shenhongxi on 2021/3/5.
 */
public interface RegistryService {

    void register(URL url);

    void unregister(URL url);
}