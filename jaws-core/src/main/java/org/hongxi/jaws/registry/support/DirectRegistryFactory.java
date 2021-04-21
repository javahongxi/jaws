package org.hongxi.jaws.registry.support;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/4/22.
 */
@SpiMeta(name = "direct")
public class DirectRegistryFactory extends AbstractRegistryFactory {

    @Override
    protected Registry createRegistry(URL url) {
        return new DirectRegistry(url);
    }


}