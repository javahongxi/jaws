package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;

/**
 * SPI factory ({@code direct}) that creates {@link DirectRegistry} instances,
 * enabling point-to-point invocation against a fixed address list without an
 * external registry center.
 *
 * @see DirectRegistry
 *
 * Created by shenhongxi on 2021/4/22.
 */
@Extension("direct")
public class DirectRegistryFactory extends AbstractRegistryFactory {

    @Override
    protected Registry createRegistry(URL url) {
        return new DirectRegistry(url);
    }
}