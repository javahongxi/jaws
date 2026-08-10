package org.hongxi.jaws.cluster.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.hongxi.jaws.cluster.Cluster;
import org.hongxi.jaws.cluster.HaStrategy;
import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.URLParamType;
import org.hongxi.jaws.common.extension.ExtensionLoader;
import org.hongxi.jaws.registry.ConfigListener;
import org.hongxi.jaws.registry.Registry;
import org.hongxi.jaws.registry.support.command.CommandFailbackRegistry;
import org.hongxi.jaws.rpc.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles dynamic config changes for ClusterSupport.
 * Supports runtime switching of loadbalance, haStrategy, and scalar params (requestTimeout, retries).
 *
 * Created by shenhongxi on 2025/8/9.
 */
public class DynamicConfigHandler<T> implements ConfigListener {

    private static final Logger log = LoggerFactory.getLogger(DynamicConfigHandler.class);

    private final URL url;
    private final Cluster<T> cluster;

    public DynamicConfigHandler(URL url, Cluster<T> cluster) {
        this.url = url;
        this.cluster = cluster;
    }

    public void subscribe(Registry registry, URL subscribeUrl) {
        if (registry instanceof CommandFailbackRegistry) {
            ((CommandFailbackRegistry) registry).subscribeConfig(subscribeUrl, this);
        }
    }

    public void unsubscribe(Registry registry, URL subscribeUrl) {
        if (registry instanceof CommandFailbackRegistry) {
            ((CommandFailbackRegistry) registry).unsubscribeConfig(subscribeUrl, this);
        }
    }

    @Override
    public void notifyConfig(URL serviceUrl, String configString) {
        if (StringUtils.isBlank(configString)) {
            log.info("DynamicConfigHandler config cleared, reverting to defaults: service={}", serviceUrl.toSimpleString());
            return;
        }
        try {
            JSONObject config = JSON.parseObject(configString);
            if (config == null || config.isEmpty()) {
                return;
            }
            log.info("DynamicConfigHandler config changed: service={}, config={}", serviceUrl.toSimpleString(), configString);
            applyDynamicConfig(config);
        } catch (Exception e) {
            log.warn("DynamicConfigHandler failed to parse config: config={}, error={}", configString, e.getMessage());
        }
    }

    private synchronized void applyDynamicConfig(JSONObject config) {
        boolean strategyChanged = false;

        // update loadBalance if changed
        String newLb = config.getString("loadBalance");
        if (StringUtils.isNotBlank(newLb)) {
            String currentLb = url.getParameter(URLParamType.loadBalance.getName(), URLParamType.loadBalance.value());
            if (!newLb.equals(currentLb)) {
                try {
                    LoadBalance<T> newLoadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(newLb);
                    // carry over existing weights
                    String weightStr = cluster.getLoadBalance() != null ? cluster.getLoadBalance().getWeightString() : null;
                    if (weightStr != null) {
                        newLoadBalance.setWeightString(weightStr);
                    }
                    url.addParameter(URLParamType.loadBalance.getName(), newLb);
                    cluster.setLoadBalance(newLoadBalance);
                    // re-feed references to the new loadBalance
                    newLoadBalance.onRefresh(cluster.getReferences());
                    strategyChanged = true;
                    log.info("DynamicConfigHandler loadBalance switched: {} -> {}", currentLb, newLb);
                } catch (Exception e) {
                    log.warn("DynamicConfigHandler failed to switch loadBalance to {}: {}", newLb, e.getMessage());
                }
            }
        }

        // update haStrategy if changed
        String newHa = config.getString("haStrategy");
        if (StringUtils.isNotBlank(newHa)) {
            String currentHa = url.getParameter(URLParamType.haStrategy.getName(), URLParamType.haStrategy.value());
            if (!newHa.equals(currentHa)) {
                try {
                    HaStrategy<T> newHaStrategy = ExtensionLoader.getExtensionLoader(HaStrategy.class).getExtension(newHa);
                    newHaStrategy.setUrl(url);
                    url.addParameter(URLParamType.haStrategy.getName(), newHa);
                    cluster.setHaStrategy(newHaStrategy);
                    strategyChanged = true;
                    log.info("DynamicConfigHandler haStrategy switched: {} -> {}", currentHa, newHa);
                } catch (Exception e) {
                    log.warn("DynamicConfigHandler failed to switch haStrategy to {}: {}", newHa, e.getMessage());
                }
            }
        }

        // update scalar params (requestTimeout, retries) — these are read from URL per call,
        // so just updating URL parameters is sufficient
        updateScalarParam(config, "requestTimeout");
        updateScalarParam(config, "retries");

        if (!strategyChanged) {
            log.info("DynamicConfigHandler config applied (scalar params only): {}", config);
        }
    }

    private void updateScalarParam(JSONObject config, String key) {
        String value = config.getString(key);
        if (StringUtils.isNotBlank(value)) {
            url.addParameter(key, value);
            log.info("DynamicConfigHandler dynamic param updated: {}={}", key, value);
        }
    }
}
