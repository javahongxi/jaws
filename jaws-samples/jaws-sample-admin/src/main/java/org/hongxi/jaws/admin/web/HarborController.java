package org.hongxi.jaws.admin.web;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.admin.config.AdminConfig;
import org.hongxi.jaws.admin.harbor.HarborNodeClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller that aggregates data from all Harbor cluster nodes.
 *
 * @author shenhongxi
 */
@RestController
@RequestMapping("/api")
public class HarborController {

    private final HarborNodeClient client;

    public HarborController(HarborNodeClient client) {
        this.client = client;
    }

    /**
     * Aggregated services from all nodes.
     * Each service entry includes a {@code nodeName} field indicating its source.
     */
    @GetMapping("/services")
    public JSONObject getServices() {
        List<AdminConfig.NodeConfig> nodes = client.getNodes();
        JSONArray allServices = new JSONArray();
        int totalServices = 0;

        for (AdminConfig.NodeConfig node : nodes) {
            JSONObject nodeData = client.fetchFromNode(node.getUrl(), "/api/services");
            if (!nodeData.getBooleanValue("error", false)) {
                totalServices += nodeData.getIntValue("totalServices", 0);
                JSONArray services = nodeData.getJSONArray("services");
                if (services != null) {
                    for (int i = 0; i < services.size(); i++) {
                        JSONObject svc = services.getJSONObject(i);
                        svc.put("nodeName", node.getName());
                        svc.put("nodeUrl", node.getUrl());
                        allServices.add(svc);
                    }
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("totalServices", totalServices);
        result.put("services", allServices);
        return result;
    }

    /**
     * Aggregated configs from all nodes, with per-node breakdown.
     */
    @GetMapping("/configs")
    public JSONObject getConfigs() {
        List<AdminConfig.NodeConfig> nodes = client.getNodes();
        JSONArray allConfigs = new JSONArray();
        JSONObject byNode = new JSONObject();  // per-node config count
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        int totalConfigs = 0;

        for (AdminConfig.NodeConfig node : nodes) {
            JSONObject nodeData = client.fetchFromNode(node.getUrl(), "/api/configs");
            if (!nodeData.getBooleanValue("error", false)) {
                int nodeConfigCount = nodeData.getIntValue("totalConfigs", 0);
                totalConfigs += nodeConfigCount;
                byNode.put(node.getName(), nodeConfigCount);
                
                JSONArray configs = nodeData.getJSONArray("configs");
                if (configs != null) {
                    for (int i = 0; i < configs.size(); i++) {
                        JSONObject cfg = configs.getJSONObject(i);
                        String key = cfg.getString("namespace") + "@@"
                                + cfg.getString("group") + "@@"
                                + cfg.getString("dataId");
                        if (seen.add(key)) {
                            allConfigs.add(cfg);
                        }
                    }
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("totalConfigs", totalConfigs);
        result.put("configs", allConfigs);
        result.put("byNode", byNode);  // per-node breakdown
        return result;
    }

    /**
     * Cluster info — fetched from the first reachable node.
     * The response already includes per-member services/connections counts.
     */
    @GetMapping("/cluster")
    public JSONObject getCluster() {
        List<AdminConfig.NodeConfig> nodes = client.getNodes();
        if (nodes.isEmpty()) {
            JSONObject empty = new JSONObject();
            empty.put("clusterSize", 0);
            empty.put("members", new JSONArray());
            return empty;
        }

        // Try each node until one responds successfully
        for (AdminConfig.NodeConfig node : nodes) {
            JSONObject result = client.fetchFromNode(node.getUrl(), "/api/cluster");
            if (!result.getBooleanValue("error", false)) {
                return result;
            }
        }

        // All nodes failed
        JSONObject error = new JSONObject();
        error.put("clusterSize", 0);
        error.put("members", new JSONArray());
        return error;
    }

    /**
     * Aggregated connections from all nodes.
     * Returns total (including cluster peer connections) and client-only count.
     */
    @GetMapping("/connections")
    public JSONObject getConnections() {
        List<AdminConfig.NodeConfig> nodes = client.getNodes();
        int totalCount = 0;
        int clientCount = 0;
        JSONArray allConnections = new JSONArray();

        for (AdminConfig.NodeConfig node : nodes) {
            JSONObject nodeData = client.fetchFromNode(node.getUrl(), "/api/connections");
            if (!nodeData.getBooleanValue("error", false)) {
                totalCount += nodeData.getIntValue("totalConnections", 0);
                clientCount += nodeData.getIntValue("clientConnections", 0);
                JSONArray conns = nodeData.getJSONArray("connections");
                if (conns != null) {
                    for (int i = 0; i < conns.size(); i++) {
                        JSONObject conn = conns.getJSONObject(i);
                        conn.put("nodeName", node.getName());
                        allConnections.add(conn);
                    }
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("totalConnections", totalCount);
        result.put("clientConnections", clientCount);
        result.put("connections", allConnections);
        return result;
    }

    /**
     * Overview — summary with per-node breakdown for consistency check.
     */
    @GetMapping("/overview")
    public JSONObject getOverview() {
        List<AdminConfig.NodeConfig> nodes = client.getNodes();
        int totalServices = 0;
        int totalConfigs = 0;
        int totalConnections = 0;
        JSONArray nodeNames = new JSONArray();
        JSONObject servicesByNode = new JSONObject();
        JSONObject configsByNode = new JSONObject();
        JSONObject connectionsByNode = new JSONObject();

        for (AdminConfig.NodeConfig node : nodes) {
            nodeNames.add(node.getName());
            
            // Fetch services
            JSONObject svcData = client.fetchFromNode(node.getUrl(), "/api/services");
            if (!svcData.getBooleanValue("error", false)) {
                int svcCount = svcData.getIntValue("totalServices", 0);
                totalServices += svcCount;
                servicesByNode.put(node.getName(), svcCount);
            }

            // Fetch configs
            JSONObject cfgData = client.fetchFromNode(node.getUrl(), "/api/configs");
            if (!cfgData.getBooleanValue("error", false)) {
                int cfgCount = cfgData.getIntValue("totalConfigs", 0);
                totalConfigs += cfgCount;
                configsByNode.put(node.getName(), cfgCount);
            }

            // Fetch connections
            JSONObject connData = client.fetchFromNode(node.getUrl(), "/api/connections");
            if (!connData.getBooleanValue("error", false)) {
                int connCount = connData.getIntValue("clientConnections", 0);
                totalConnections += connCount;
                connectionsByNode.put(node.getName(), connCount);
            }
        }

        JSONObject overview = new JSONObject();
        overview.put("totalServices", totalServices);
        overview.put("totalConfigs", totalConfigs);
        overview.put("clientConnections", totalConnections);
        overview.put("totalNodes", nodes.size());
        overview.put("nodeNames", nodeNames);
        overview.put("servicesByNode", servicesByNode);  // per-node service count
        overview.put("configsByNode", configsByNode);    // per-node config count
        overview.put("connectionsByNode", connectionsByNode);  // per-node connection count
        return overview;
    }
}
