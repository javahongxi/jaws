package org.hongxi.jaws.registry;

import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.rpc.URL;

/**
 * Created by shenhongxi on 2021/4/21.
 */
@Extension("local")
public class LocalRegistryFactory extends AbstractRegistryFactory {

    @Override
    protected Registry createRegistry(URL url) {
        return new LocalRegistry(url);
    }
}