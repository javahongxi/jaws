package org.hongxi.jaws.admin.harbor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.hongxi.jaws.admin.config.AdminConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * RestTemplate-based client that calls each Harbor node's dashboard JSON API.
 * <p>
 * Each call returns the parsed JSON response, or an error object if the node is unreachable.
 *
 * @author shenhongxi
 */
@Component
public class HarborNodeClient {

    private static final Logger log = LoggerFactory.getLogger(HarborNodeClient.class);
    private static final int TIMEOUT_MS = (int) Duration.ofSeconds(5).toMillis();

    private final AdminConfig config;
    private final RestTemplate restTemplate;

    public HarborNodeClient(AdminConfig config) {
        this.config = config;
        this.restTemplate = createRestTemplate();
    }

    private RestTemplate createRestTemplate() {
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {{
            setConnectTimeout(TIMEOUT_MS);
            setReadTimeout(TIMEOUT_MS);
        }};
        return new RestTemplate(factory);
    }

    public List<AdminConfig.NodeConfig> getNodes() {
        return config.getNodes();
    }

    /**
     * Fetch a JSON API path from a specific Harbor node.
     *
     * @param nodeUrl the node's dashboard base URL (e.g. "http://127.0.0.1:19849")
     * @param path    the API path (e.g. "/api/services")
     * @return parsed JSON object, or error object on failure
     */
    public JSONObject fetchFromNode(String nodeUrl, String path) {
        try {
            String url = nodeUrl + path;
            String response = restTemplate.getForObject(url, String.class);
            return response != null ? JSON.parseObject(response) : createError("empty response");
        } catch (Exception ex) {
            log.debug("[admin] failed to fetch {} from {}: {}", path, nodeUrl, ex.getMessage());
            return createError(ex.getMessage());
        }
    }

    private JSONObject createError(String message) {
        JSONObject error = new JSONObject();
        error.put("error", true);
        error.put("message", message);
        return error;
    }
}
