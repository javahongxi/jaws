package org.hongxi.jaws.registry.harbor;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.registry.AbstractRegistryFactory;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates {@link HarborRegistry} for {@code harbor://host:port} registry URLs — the
 * address list may hold several nodes, same shape as {@code serverAddr} in Nacos.
 *
 * @author shenhongxi
 */
@Extension(JawsConstants.REGISTRY_PROTOCOL_HARBOR)
public class HarborRegistryFactory extends AbstractRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(HarborRegistryFactory.class);

    @Override
    protected Registry createRegistry(URL registryUrl) {
        String address = registryUrl.getBackupAddress();
        try {
            // Tenant stays at the client default: NacosPathUtils already carries the
            // service namespace inside the service name, so a second one here would
            // have to agree with the nacos leg anyway and only add a way to diverge.
            HarborClientConfig config = HarborClientConfig.ofCluster(address);
            log.info("creating harbor registry client for {}", config.allAddresses());
            return new HarborRegistry(registryUrl, new HarborClient(config));
        } catch (Exception e) {
            log.error("failed to connect harbor registry {}", address, e);
            throw new IllegalStateException("failed to connect harbor registry " + address, e);
        }
    }
}
