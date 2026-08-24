package org.hongxi.jaws.registry;

import org.hongxi.jaws.rpc.URL;

/**
 * Used to register and discover. Instances are stateful and bound to one
 * registry URL, so they are created per URL by {@link RegistryFactory}
 * rather than loaded as an SPI extension.
 * <p>
 * Created by shenhongxi on 2021/3/7.
 */
public interface Registry extends RegistryService, DiscoveryService {

    URL getUrl();
}