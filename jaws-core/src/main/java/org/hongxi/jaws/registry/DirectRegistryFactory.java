package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/4/22.
 */
@Extension(name = "direct")
public class DirectRegistryFactory extends AbstractRegistryFactory {

    @Override
    protected Registry createRegistry(URL url) {
        return new DirectRegistry(url);
    }
}