package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * Service discovery facet of the registry: consumers subscribe to service
 * changes with a {@link NotifyListener}, unsubscribe to stop notifications,
 * or perform a one-shot {@link #discover(URL)} lookup.
 * <p>
 * Combined with {@link RegistryService}, this forms the full registry
 * contract aggregated by {@link Registry}.
 *
 * @see NotifyListener
 * @see RegistryService
 *
 * Created by shenhongxi on 2021/3/7.
 */
public interface DiscoveryService {

    void subscribe(URL url, NotifyListener listener);

    void unsubscribe(URL url, NotifyListener listener);

    List<URL> discover(URL url);
}