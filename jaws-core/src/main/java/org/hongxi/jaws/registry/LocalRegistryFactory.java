package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;

/**
 * SPI factory ({@code local}) that creates {@link LocalRegistry} instances,
 * enabling service registration and discovery within a single JVM without
 * an external registry center.
 *
 * @see LocalRegistry
 *
 * Created by shenhongxi on 2021/4/21.
 */
@Extension("local")
public class LocalRegistryFactory extends AbstractRegistryFactory {

    @Override
    protected Registry createRegistry(URL url) {
        return new LocalRegistry(url);
    }
}