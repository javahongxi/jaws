package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

/**
 * To create registry
 *
 * Created by shenhongxi on 2021/3/7.
 */
@Spi(scope = Scope.SINGLETON)
public interface RegistryFactory {

    Registry getRegistry(URL url);
}