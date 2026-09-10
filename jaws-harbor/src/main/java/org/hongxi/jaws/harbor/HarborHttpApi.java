package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.config.ConfigStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP/1.1 JSON API for Harbor management (consumed by jaws-sample-admin).
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /api/services} — all registered services</li>
 *   <li>{@code GET /api/configs} — all configurations</li>
 *   <li>{@code GET /api/cluster} — cluster members</li>
 *   <li>{@code GET /api/connections} — active connection count</li>
 * </ul>
 * Runs on {@code grpcPort + 1}, zero external dependencies (JDK HttpServer only).
 *
 * @author shenhongxi
 */
public class HarborHttpApi {

    private static final Logger log = LoggerFactory.getLogger(HarborHttpApi.class);

    private final HttpServer httpServer;
    private final HarborServer harborServer;

    public HarborHttpApi(HarborServer harborServer, int port) throws IOException {
        this.harborServer = harborServer;
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.httpServer.createContext("/api/services", new ServicesHandler());
        this.httpServer.createContext("/api/configs", new ConfigsHandler());
        this.httpServer.createContext("/api/cluster", new ClusterHandler());
        this.httpServer.createContext("/api/connections", new ConnectionsHandler());
        this.httpServer.setExecutor(null); // use daemon threads
    }

    public void start() {
        httpServer.start();
        log.info("[harbor-http] management API started on port {}", httpServer.getAddress().getPort());
    }

    public void stop() {
        httpServer.stop(0);
        log.info("[harbor-http] management API stopped");
    }

    public int getPort() {
        return httpServer.getAddress().getPort();
    }

    private class ServicesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }

            ServiceStorage storage = harborServer.getServiceStorage();
            Map<String, List<JSONObject>> allData = storage.getAllInstanceData();
            JSONArray services = new JSONArray();
            int totalServices = 0;

            for (Map.Entry<String, List<JSONObject>> entry : allData.entrySet()) {
                JSONObject svc = new JSONObject();
                svc.put("serviceName", entry.getKey());
                svc.put("instances", entry.getValue());
                services.add(svc);
                totalServices++;
            }

            JSONObject result = new JSONObject();
            result.put("totalServices", totalServices);
            result.put("services", services);
            sendJson(exchange, 200, result.toJSONString());
        }
    }

    private class ConfigsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }

            ConfigStorage storage = harborServer.getConfigStorage();
            Map<String, ConfigStorage.ConfigRecord> allConfigs = storage.getAllConfigs();
            JSONArray configs = new JSONArray();
            int totalConfigs = 0;

            for (Map.Entry<String, ConfigStorage.ConfigRecord> entry : allConfigs.entrySet()) {
                JSONObject cfg = new JSONObject();
                cfg.put("dataId", entry.getValue().dataId());
                cfg.put("group", entry.getValue().group());
                cfg.put("namespace", entry.getValue().namespace());
                cfg.put("content", entry.getValue().content());
                cfg.put("md5", entry.getValue().md5());
                cfg.put("lastModified", entry.getValue().lastModified());
                cfg.put("type", entry.getValue().type());
                configs.add(cfg);
                totalConfigs++;
            }

            JSONObject result = new JSONObject();
            result.put("totalConfigs", totalConfigs);
            result.put("configs", configs);
            sendJson(exchange, 200, result.toJSONString());
        }
    }

    private class ClusterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }

            ClusterManager cm = harborServer.getClusterManager();
            String selfAddr = cm.getSelfAddress();

            JSONArray members = new JSONArray();
            for (ClusterMember m : cm.allMembers()) {
                JSONObject obj = new JSONObject();
                obj.put("address", m.address());
                obj.put("host", m.host());
                obj.put("port", m.port());
                obj.put("self", m.address().equals(selfAddr));
                obj.put("services", 0); // placeholder
                obj.put("connections", 0); // placeholder
                members.add(obj);
            }

            JSONObject result = new JSONObject();
            result.put("clusterSize", members.size());
            result.put("members", members);
            sendJson(exchange, 200, result.toJSONString());
        }
    }

    private class ConnectionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }

            ConnectionManager connMgr = harborServer.getConnectionManager();
            int activeConnections = connMgr.size();

            JSONObject result = new JSONObject();
            result.put("activeConnections", activeConnections);
            sendJson(exchange, 200, result.toJSONString());
        }
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
