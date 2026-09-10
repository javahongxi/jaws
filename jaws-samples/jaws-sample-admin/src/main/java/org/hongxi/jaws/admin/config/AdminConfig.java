package org.hongxi.jaws.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the Jaws Admin console.
 * <p>
 * Reads the {@code admin.harbor.nodes} list from {@code application.yml}:
 * <pre>
 * admin:
 *   harbor:
 *     nodes:
 *       - name: node-1
 *         url: http://127.0.0.1:19849
 *       - name: node-2
 *         url: http://127.0.0.1:19851
 * </pre>
 *
 * @author shenhongxi
 */
@ConfigurationProperties(prefix = "admin.harbor")
public class AdminConfig {

    private List<NodeConfig> nodes = new ArrayList<>();

    public List<NodeConfig> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeConfig> nodes) {
        this.nodes = nodes;
    }

    /**
     * A single Harbor server node entry.
     */
    public static class NodeConfig {

        /** Display name shown in the dashboard (e.g. "node-1"). */
        private String name;

        /** Dashboard base URL of the Harbor node (e.g. "http://127.0.0.1:19849"). */
        private String url;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
