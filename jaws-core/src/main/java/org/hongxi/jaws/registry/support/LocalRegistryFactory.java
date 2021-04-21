package org.hongxi.jaws.registry.support;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.rpc.URL;

@SpiMeta(name = "local")
public class LocalRegistryFactory extends AbstractRegistryFactory {

    @Override
    protected Registry createRegistry(URL url) {
        return ExtensionLoader.getExtensionLoader(Registry.class).getExtension(JawsConstants.REGISTRY_PROTOCOL_LOCAL);
    }
}