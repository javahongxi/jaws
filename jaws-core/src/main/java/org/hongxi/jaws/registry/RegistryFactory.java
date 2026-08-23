package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.Spi;
import org.hongxi.jaws.rpc.URL;

/**
 * To create registry
 * <p>
 * Created by shenhongxi on 2021/3/7.
 */
@Spi(singleton = true)
public interface RegistryFactory {

    Registry getRegistry(URL url);
}