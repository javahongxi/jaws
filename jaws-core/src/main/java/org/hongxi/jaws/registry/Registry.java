package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

/**
 * Used to register and discover.
 *
 * Created by shenhongxi on 2021/3/7.
 */
@Spi(scope = Scope.SINGLETON)
public interface Registry extends RegistryService, DiscoveryService {

    URL getUrl();
}