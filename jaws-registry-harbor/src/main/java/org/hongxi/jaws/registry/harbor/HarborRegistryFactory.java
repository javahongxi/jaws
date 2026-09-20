package org.hongxi.jaws.registry.harbor;

import org.hongxi.jaws.common.JawsConstants;
import org.hongxi.jaws.common.extension.Extension;
import org.hongxi.jaws.configcenter.DynamicConfigurationUtils;
import org.hongxi.jaws.harbor.client.HarborClient;
import org.hongxi.jaws.harbor.client.HarborClientConfig;
import org.hongxi.jaws.harbor.model.request.DynamicConfigChangeRequest;
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
            HarborClient client = new HarborClient(config);
            wireDynamicConfigBroadcast(client);
            return new HarborRegistry(registryUrl, client);
        } catch (Exception e) {
            log.error("failed to connect harbor registry {}", address, e);
            throw new IllegalStateException("failed to connect harbor registry " + address, e);
        }
    }

    /**
     * Bind harbor's demo dynamic-config broadcast to this process's configuration.
     * The client only decodes the frame it speaks; here — in the registry leg, not
     * the SDK — we decide what it means: apply each change to the running
     * {@link DynamicConfigurationUtils} so framework hot-config listeners fire. This
     * is deliberately NOT in the client: the source guard keeps {@code client/} free
     * of the config-center dependency so the SDK stays a pure protocol client.
     */
    private static void wireDynamicConfigBroadcast(HarborClient client) {
        client.setDynamicConfigListener(HarborRegistryFactory::applyConfigChange);
    }

    private static void applyConfigChange(DynamicConfigChangeRequest change) {
        if (change.isDeleted()) {
            DynamicConfigurationUtils.removeConfig(change.getKey());
        } else if (change.getValue() != null) {
            DynamicConfigurationUtils.setConfig(change.getKey(), change.getValue());
        }
    }
}
