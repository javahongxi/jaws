package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.config.ConfigStorage;
import org.hongxi.jaws.harbor.distro.DistroConfig;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.GrpcHarborNodeTransport;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamObserver;
import org.hongxi.jaws.stream.StreamSource;
import org.hongxi.jaws.transport.StreamSubject;
import org.hongxi.jaws.wire.WireCallContext;
import org.hongxi.jaws.wire.WireHandlerRegistry;
import org.hongxi.jaws.wire.WireMethodHandler;
import org.hongxi.jaws.wire.WireServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jaws Harbor — a Nacos-compatible control plane server.
 * <p>
 * Implements the Nacos 2.x gRPC protocol on top of the Jaws wire transport:
 * <ul>
 *   <li>{@code Request.request} — unary RPC for all client requests
 *       (ServerCheck, InstanceRequest, SubscribeServiceRequest, etc.)</li>
 *   <li>{@code BiRequestStream.requestBiStream} — bidirectional stream for
 *       connection setup and server push notifications</li>
 * </ul>
 * The Payload wire format (JSON body wrapped in {@code google.protobuf.Any})
 * is fully compatible with nacos-client, so Jaws services can register with
 * jaws-harbor using the standard {@code nacos-client} SDK.
 * <p>
 * Phase 2 adds the Distro protocol for multi-node replication and a
 * config center with in-memory storage and listener push notifications.
 *
 * @author shenhongxi
 */
public class HarborServer {

    private static final Logger log = LoggerFactory.getLogger(HarborServer.class);

    private static final String SERVICE_NAME_REQUEST = "Request";
    private static final String SERVICE_NAME_BI_STREAM = "BiRequestStream";
    private static final String METHOD_REQUEST = "request";
    private static final String METHOD_BI_STREAM = "requestBiStream";

    // Nacos naming request types
    private static final String TYPE_SERVER_CHECK_REQUEST = "ServerCheckRequest";
    private static final String TYPE_CONNECTION_SETUP_REQUEST = "ConnectionSetupRequest";
    private static final String TYPE_INSTANCE_REQUEST = "InstanceRequest";
    private static final String TYPE_SUBSCRIBE_SERVICE_REQUEST = "SubscribeServiceRequest";
    private static final String TYPE_SERVICE_QUERY_REQUEST = "ServiceQueryRequest";
    private static final String TYPE_SERVICE_LIST_REQUEST = "ServiceListRequest";
    private static final String TYPE_NOTIFY_SUBSCRIBER_RESPONSE = "NotifySubscriberResponse";
    private static final String TYPE_HEALTH_CHECK_REQUEST = "HealthCheckRequest";

    // Nacos config request types
    private static final String TYPE_CONFIG_PUBLISH_REQUEST = "ConfigPublishRequest";
    private static final String TYPE_CONFIG_QUERY_REQUEST = "ConfigQueryRequest";
    private static final String TYPE_CONFIG_REMOVE_REQUEST = "ConfigRemoveRequest";
    private static final String TYPE_CONFIG_BATCH_LISTEN_REQUEST = "ConfigBatchListenRequest";
    private static final String TYPE_CONFIG_CHANGE_NOTIFY_RESPONSE = "ConfigChangeNotifyResponse";

    // Distro inter-node request types
    private static final String TYPE_DISTRO_SYNC_REQUEST = "DistroSyncRequest";
    private static final String TYPE_DISTRO_VERIFY_REQUEST = "DistroVerifyRequest";
    private static final String TYPE_DISTRO_SNAPSHOT_REQUEST = "DistroSnapshotRequest";

    // Nacos naming response types
    private static final String TYPE_SERVER_CHECK_RESPONSE = "ServerCheckResponse";
    private static final String TYPE_INSTANCE_RESPONSE = "InstanceResponse";
    private static final String TYPE_SUBSCRIBE_SERVICE_RESPONSE = "SubscribeServiceResponse";
    private static final String TYPE_QUERY_SERVICE_RESPONSE = "QueryServiceResponse";
    private static final String TYPE_SERVICE_LIST_RESPONSE = "ServiceListResponse";
    private static final String TYPE_NOTIFY_SUBSCRIBER_REQUEST = "NotifySubscriberRequest";
    private static final String TYPE_HEALTH_CHECK_RESPONSE = "HealthCheckResponse";

    // Nacos config response types
    private static final String TYPE_CONFIG_PUBLISH_RESPONSE = "ConfigPublishResponse";
    private static final String TYPE_CONFIG_QUERY_RESPONSE = "ConfigQueryResponse";
    private static final String TYPE_CONFIG_REMOVE_RESPONSE = "ConfigRemoveResponse";
    private static final String TYPE_CONFIG_BATCH_LISTEN_RESPONSE = "ConfigBatchListenResponse";
    private static final String TYPE_CONFIG_CHANGE_NOTIFY_REQUEST = "ConfigChangeNotifyRequest";

    // Distro inter-node response types
    private static final String TYPE_DISTRO_SYNC_RESPONSE = "DistroSyncResponse";
    private static final String TYPE_DISTRO_VERIFY_RESPONSE = "DistroVerifyResponse";
    private static final String TYPE_DISTRO_SNAPSHOT_RESPONSE = "DistroSnapshotResponse";

    // Nacos naming remote constants
    private static final String REGISTER_INSTANCE = "registerInstance";
    private static final String DE_REGISTER_INSTANCE = "deregisterInstance";

    /**
     * URL parameter for specifying initial cluster members.
     * Format: comma-separated {@code host:port} pairs,
     * e.g. {@code "10.0.0.1:19848,10.0.0.2:19848"}.
     */
    public static final String PARAM_CLUSTER_MEMBERS = "clusterMembers";

    private final ConnectionManager connectionManager = new ConnectionManager();
    private final ServiceStorage serviceStorage;
    private final ConfigStorage configStorage;
    private final ClusterManager clusterManager;
    private final DistroProtocol distroProtocol;
    private final WireServer wireServer;
    private final HealthCheckManager healthCheckManager;
    private HarborHttpApi httpApi;

    /**
     * Maps clientIp → connectionId so that the ServerCheck (unary) and
     * BiRequestStream (bidi) on the same TCP connection share the same ID.
     */
    private final Map<String, String> connectionIdByClientIp = new ConcurrentHashMap<>();

    public HarborServer(URL url) {
        this(url, new GrpcHarborNodeTransport());
    }

    public HarborServer(URL url, HarborNodeTransport transport) {
        this.configStorage = new ConfigStorage(this::notifyConfigListener);
        this.serviceStorage = new ServiceStorage(this::notifySubscriber);
        this.healthCheckManager = new HealthCheckManager(this.serviceStorage);
        this.clusterManager = new ClusterManager();
        String selfAddr = resolveSelfAddress(url.getHost()) + ":" + url.getPort();
        this.clusterManager.setSelfAddress(selfAddr);

        DistroConfig distroConfig = new DistroConfig();
        this.distroProtocol = new DistroProtocol(clusterManager, distroConfig, transport,
                serviceStorage, configStorage);

        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register(SERVICE_NAME_REQUEST, METHOD_REQUEST, new RequestHandler());
        registry.register(SERVICE_NAME_BI_STREAM, METHOD_BI_STREAM, new BiStreamHandler());

        this.wireServer = new WireServer(url, registry);
    }

    public void start() {
        // Register self as a cluster member so the dashboard can show it
        String selfAddr = clusterManager.getSelfAddress();
        clusterManager.addMember(new ClusterMember(selfAddr));

        // Auto-join cluster members from URL parameter
        String clusterMembersParam = wireServer.getUrl().getParameter(PARAM_CLUSTER_MEMBERS);
        if (clusterMembersParam != null && !clusterMembersParam.isEmpty()) {
            for (String addr : clusterMembersParam.split(",")) {
                String trimmed = addr.trim();
                if (!trimmed.isEmpty()) {
                    addClusterMember(trimmed);
                }
            }
            log.info("[harbor] auto-joined cluster with {} members", clusterManager.size());
        }

        wireServer.open();
        distroProtocol.start();
        healthCheckManager.start();

        // Start HTTP/1.1 management API for jaws-sample-admin (port from URL param, default grpcPort + 10)
        int grpcPort = wireServer.getUrl().getPort();
        String httpApiPortStr = wireServer.getUrl().getParameter("httpApiPort");
        int httpApiPort = httpApiPortStr != null ? Integer.parseInt(httpApiPortStr) : grpcPort + 10;
        if (httpApiPort > 0) {
            try {
                httpApi = new HarborHttpApi(this, httpApiPort);
                httpApi.start();
            } catch (Exception e) {
                log.warn("[harbor] failed to start HTTP management API on port {}: {}",
                        httpApiPort, e.getMessage());
            }
        }

        log.info("[harbor] server started on port {} (cluster size={})",
                wireServer.getUrl().getPort(), clusterManager.size());
    }

    public void close() {
        if (httpApi != null) {
            httpApi.stop();
        }
        healthCheckManager.shutdown();
        distroProtocol.shutdown();
        wireServer.close();
        log.info("[harbor] server closed");
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public ServiceStorage getServiceStorage() {
        return serviceStorage;
    }

    public ConfigStorage getConfigStorage() {
        return configStorage;
    }

    public ClusterManager getClusterManager() {
        return clusterManager;
    }

    public DistroProtocol getDistroProtocol() {
        return distroProtocol;
    }

    /**
     * Add a peer node to the cluster for Distro data synchronization.
     * Must be called before or after {@link #start()}; the Distro verify
     * cycle will pick up new members automatically.
     *
     * @param address peer address in {@code host:port} form
     */
    public void addClusterMember(String address) {
        clusterManager.addMember(new ClusterMember(address));
        log.info("[harbor] cluster member added: {} (cluster size={})",
                address, clusterManager.size());
    }

    /**
     * Resolve the bind address {@code 0.0.0.0} to the local hostname so that
     * the cluster self-address is human-readable and reachable by peers.
     */
    private static String resolveSelfAddress(String host) {
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            try {
                return java.net.InetAddress.getLocalHost().getHostAddress();
            } catch (java.net.UnknownHostException e) {
                return "127.0.0.1";
            }
        }
        return host;
    }

    // ========================================================================
    // Payload helpers
    // ========================================================================

    static Payload buildPayload(String type, JSONObject body) {
        byte[] jsonBytes = JSON.toJSONBytes(body);
        return Payload.newBuilder()
                .setMetadata(org.hongxi.jaws.harbor.proto.Metadata.newBuilder()
                        .setType(type)
                        .build())
                .setBody(Any.newBuilder()
                        .setValue(ByteString.copyFrom(jsonBytes))
                        .build())
                .build();
    }

    static Payload buildPayload(String type, JSONObject body, String clientIp) {
        byte[] jsonBytes = JSON.toJSONBytes(body);
        return Payload.newBuilder()
                .setMetadata(org.hongxi.jaws.harbor.proto.Metadata.newBuilder()
                        .setType(type)
                        .setClientIp(clientIp)
                        .build())
                .setBody(Any.newBuilder()
                        .setValue(ByteString.copyFrom(jsonBytes))
                        .build())
                .build();
    }

    static JSONObject parseBody(Payload payload) {
        byte[] bytes = payload.getBody().getValue().toByteArray();
        if (bytes.length == 0) {
            return new JSONObject();
        }
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    // ========================================================================
    // Request.request handler (unary)
    // ========================================================================

    /**
     * Handles all unary {@code Request.request(Payload)} calls.
     * Dispatches based on {@code Payload.metadata.type} to the appropriate
     * naming handler.
     */
    private class RequestHandler implements WireMethodHandler {

        @Override
        public Message handle(Message request) {
            return handle(request, null);
        }

        @Override
        public Message handle(Message request, WireCallContext context) {
            Payload payload = (Payload) request;
            String type = payload.getMetadata().getType();
            String clientIp = payload.getMetadata().getClientIp();

            // Update heartbeat for all instances from this client (Nacos connection-level model)
            serviceStorage.updateHeartbeatByClientIp(clientIp);

            try {
                return switch (type) {
                    // Naming
                    case TYPE_SERVER_CHECK_REQUEST -> handleServerCheck(clientIp);
                    case TYPE_INSTANCE_REQUEST -> handleInstanceRequest(payload, clientIp);
                    case TYPE_SUBSCRIBE_SERVICE_REQUEST -> handleSubscribe(payload, clientIp);
                    case TYPE_SERVICE_QUERY_REQUEST -> handleServiceQuery(payload);
                    case TYPE_SERVICE_LIST_REQUEST -> handleServiceList(payload);
                    case TYPE_HEALTH_CHECK_REQUEST -> handleHealthCheck(payload);
                    // Config
                    case TYPE_CONFIG_PUBLISH_REQUEST -> handleConfigPublish(payload, clientIp);
                    case TYPE_CONFIG_QUERY_REQUEST -> handleConfigQuery(payload, clientIp);
                    case TYPE_CONFIG_REMOVE_REQUEST -> handleConfigRemove(payload, clientIp);
                    case TYPE_CONFIG_BATCH_LISTEN_REQUEST -> handleConfigBatchListen(payload, clientIp);
                    // Distro inter-node
                    case TYPE_DISTRO_SYNC_REQUEST -> handleDistroSync(payload);
                    case TYPE_DISTRO_VERIFY_REQUEST -> handleDistroVerify(payload);
                    case TYPE_DISTRO_SNAPSHOT_REQUEST -> handleDistroSnapshot(payload);
                    default -> {
                        log.warn("[harbor] unknown request type: {}", type);
                        yield buildErrorResponse(type, "Unknown request type: " + type);
                    }
                };
            } catch (Exception e) {
                log.error("[harbor] error handling request type={}", type, e);
                return buildErrorResponse(type, e.getMessage());
            }
        }

        @Override
        public Parser<? extends Message> getRequestParser() {
            return Payload.getDefaultInstance().getParserForType();
        }
    }

    // ========================================================================
    // BiRequestStream.requestBiStream handler (bidirectional)
    // ========================================================================

    /**
     * Handles the bidirectional {@code BiRequestStream.requestBiStream} stream.
     * <p>
     * The client opens this stream after ServerCheck and sends a
     * {@code ConnectionSetupRequest} with client metadata. The server uses
     * this stream to push {@code NotifySubscriberRequest} messages when
     * service instances change.
     */
    private class BiStreamHandler implements WireMethodHandler {

        @Override
        public MethodType methodType() {
            return MethodType.BIDIRECTIONAL;
        }

        @Override
        public Message handle(Message request) {
            throw new UnsupportedOperationException("bidi stream");
        }

        @Override
        public StreamSource<Message> handleBiStream(StreamSource<Message> requestStream) {
            return handleBiStream(requestStream, null);
        }

        @Override
        public StreamSource<Message> handleBiStream(StreamSource<Message> requestStream,
                                                     WireCallContext context) {
            // Create a push subject for server→client notifications
            StreamSubject<Message> pushSubject = new StreamSubject<>();

            // Process incoming client messages (ConnectionSetupRequest, acks, etc.)
            requestStream.subscribe(new StreamObserver<>() {
                private String connectionId;

                @Override
                public void onNext(Message item) {
                    if (!(item instanceof Payload payload)) {
                        return;
                    }
                    String type = payload.getMetadata().getType();
                    String clientIp = payload.getMetadata().getClientIp();

                    switch (type) {
                        case TYPE_CONNECTION_SETUP_REQUEST -> {
                            JSONObject body = parseBody(payload);
                            // Look up the connectionId assigned during ServerCheck
                            connectionId = connectionIdByClientIp.get(clientIp);
                            if (connectionId == null) {
                                connectionId = UUID.randomUUID().toString();
                            }
                            String version = body.getString("clientVersion");
                            // noinspection unchecked
                            Map<String, String> labels = (Map<String, String>) body.get("labels");
                            if (labels == null) {
                                labels = Map.of();
                            }
                            connectionManager.register(connectionId, clientIp, version,
                                    labels, pushSubject);
                        }
                        case TYPE_NOTIFY_SUBSCRIBER_RESPONSE ->
                            // Client ack for a NotifySubscriberRequest — no action needed
                                log.debug("[harbor] received NotifySubscriberResponse ack");
                        case TYPE_CONFIG_CHANGE_NOTIFY_RESPONSE ->
                            // Client ack for a ConfigChangeNotifyRequest — no action needed
                                log.debug("[harbor] received ConfigChangeNotifyResponse ack");
                        default -> log.debug("[harbor] bi-stream received type={}", type);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.info("[harbor] bi-stream error: {}", throwable.getMessage());
                    if (connectionId != null) {
                        cleanupConnection(connectionId);
                    }
                }

                @Override
                public void onCompleted() {
                    log.info("[harbor] bi-stream completed for connId={}", connectionId);
                    if (connectionId != null) {
                        cleanupConnection(connectionId);
                    }
                    pushSubject.onCompleted();
                }

                private void cleanupConnection(String connId) {
                    connectionManager.remove(connId);
                    serviceStorage.removeAllSubscribersForConnection(connId);
                    configStorage.removeAllListenersForConnection(connId);
                }
            });

            // Return the push subject as the response source.
            // This source stays subscribed for the lifetime of the connection,
            // emitting NotifySubscriberRequest messages when instances change.
            return pushSubject;
        }

        @Override
        public Parser<? extends Message> getRequestParser() {
            return Payload.getDefaultInstance().getParserForType();
        }
    }

    // ========================================================================
    // Individual request handlers
    // ========================================================================

    private Payload handleServerCheck(String clientIp) {
        String connectionId = UUID.randomUUID().toString();
        if (clientIp != null && !clientIp.isEmpty()) {
            connectionIdByClientIp.put(clientIp, connectionId);
        }
        JSONObject body = new JSONObject();
        body.put("connectionId", connectionId);
        body.put("supportAbilityNegotiation", false);
        body.put("resultCode", 200);
        body.put("success", true);
        return buildPayload(TYPE_SERVER_CHECK_RESPONSE, body, clientIp);
    }

    private Payload handleInstanceRequest(Payload payload, String clientIp) {
        JSONObject body = parseBody(payload);
        String namespace = body.getString("namespace");
        String serviceName = body.getString("serviceName");
        String groupName = body.getString("groupName");
        String type = body.getString("type");

        JSONObject instance = body.getJSONObject("instance");
        if (instance == null) {
            return buildErrorResponse(TYPE_INSTANCE_RESPONSE, "Missing instance");
        }

        // Set default instanceId if not provided
        if (!instance.containsKey("instanceId") || instance.getString("instanceId") == null) {
            String groupedName = groupName + "@@" + serviceName;
            instance.put("instanceId",
                    instance.getString("ip") + "#" + instance.getIntValue("port")
                            + "#" + groupedName);
        }

        if (REGISTER_INSTANCE.equals(type)) {
            serviceStorage.registerInstance(namespace, groupName, serviceName, instance);
            // Trigger distro sync to peers
            String key = namespace + "@@" + groupName + "@@" + serviceName;
            JSONObject syncBody = new JSONObject();
            syncBody.put("instance", instance);
            distroProtocol.syncNamingChange(key, DistroProtocol.OP_CHANGE,
                    JSON.toJSONBytes(syncBody));
        } else if (DE_REGISTER_INSTANCE.equals(type)) {
            serviceStorage.deregisterInstance(namespace, groupName, serviceName, instance);
            String key = namespace + "@@" + groupName + "@@" + serviceName;
            distroProtocol.syncNamingChange(key, DistroProtocol.OP_DELETE, new byte[0]);
        } else {
            return buildErrorResponse(TYPE_INSTANCE_RESPONSE,
                    "Unknown instance operation type: " + type);
        }

        JSONObject response = new JSONObject();
        response.put("type", type);
        response.put("resultCode", 200);
        response.put("success", true);
        return buildPayload(TYPE_INSTANCE_RESPONSE, response, clientIp);
    }

    private Payload handleSubscribe(Payload payload, String clientIp) {
        JSONObject body = parseBody(payload);
        String namespace = body.getString("namespace");
        String serviceName = body.getString("serviceName");
        String groupName = body.getString("groupName");
        boolean subscribe = body.getBooleanValue("subscribe", true);
        String clusters = body.getString("clusters");

        // Find the connectionId for this client
        String connectionId = connectionIdByClientIp.get(clientIp);
        if (connectionId != null && subscribe) {
            serviceStorage.addSubscriber(namespace, groupName, serviceName, connectionId);
        } else if (connectionId != null) {
            serviceStorage.removeSubscriber(namespace, groupName, serviceName, connectionId);
        }

        JSONObject serviceInfo = serviceStorage.buildServiceInfo(
                namespace, groupName, serviceName);

        JSONObject response = new JSONObject();
        response.put("resultCode", 200);
        response.put("success", true);
        response.put("serviceInfo", serviceInfo);
        return buildPayload(TYPE_SUBSCRIBE_SERVICE_RESPONSE, response, clientIp);
    }

    private Payload handleServiceQuery(Payload payload) {
        JSONObject body = parseBody(payload);
        String namespace = body.getString("namespace");
        String serviceName = body.getString("serviceName");
        String groupName = body.getString("groupName");

        JSONObject serviceInfo = serviceStorage.buildServiceInfo(
                namespace, groupName, serviceName);

        JSONObject response = new JSONObject();
        response.put("resultCode", 200);
        response.put("success", true);
        response.put("serviceInfo", serviceInfo);
        return buildPayload(TYPE_QUERY_SERVICE_RESPONSE, response);
    }

    private Payload handleServiceList(Payload payload) {
        JSONObject body = parseBody(payload);
        String namespace = body.getString("namespace");
        String groupName = body.getString("groupName");
        if (groupName == null || groupName.isEmpty()) {
            groupName = "DEFAULT_GROUP";
        }

        List<String> services = serviceStorage.listServices(namespace, groupName);

        JSONObject response = new JSONObject();
        response.put("resultCode", 200);
        response.put("success", true);
        response.put("count", services.size());
        response.put("serviceNames", services);
        return buildPayload(TYPE_SERVICE_LIST_RESPONSE, response);
    }

    private Payload handleHealthCheck(Payload payload) {
        JSONObject response = new JSONObject();
        response.put("status", "SERVING");
        return buildPayload(TYPE_HEALTH_CHECK_RESPONSE, response);
    }

    // ========================================================================
    // Config handlers
    // ========================================================================

    private Payload handleConfigPublish(Payload payload, String clientIp) {
        JSONObject body = parseBody(payload);
        String dataId = body.getString("dataId");
        String group = body.getString("group");
        String tenant = body.getString("tenant");
        if (tenant == null || tenant.isEmpty()) {
            tenant = "public";
        }
        String content = body.getString("content");
        String type = body.containsKey("additionMap")
                ? body.getJSONObject("additionMap").getString("type")
                : null;

        boolean ok = configStorage.publishConfig(tenant, dataId, group, content, type);

        // Trigger distro sync to peers
        if (ok) {
            String key = tenant + "@@" + dataId + "@@" + group;
            JSONObject syncData = new JSONObject();
            syncData.put("content", content);
            syncData.put("type", type != null ? type : "text");
            distroProtocol.syncConfigChange(key, DistroProtocol.OP_CHANGE,
                    JSON.toJSONBytes(syncData));
        }

        JSONObject response = new JSONObject();
        response.put("resultCode", ok ? 200 : 500);
        response.put("success", ok);
        return buildPayload(TYPE_CONFIG_PUBLISH_RESPONSE, response, clientIp);
    }

    private Payload handleConfigQuery(Payload payload, String clientIp) {
        JSONObject body = parseBody(payload);
        String dataId = body.getString("dataId");
        String group = body.getString("group");
        String tenant = body.getString("tenant");
        if (tenant == null || tenant.isEmpty()) {
            tenant = "public";
        }

        ConfigStorage.ConfigRecord record = configStorage.queryConfig(tenant, dataId, group);

        JSONObject response = new JSONObject();
        response.put("resultCode", 200);
        response.put("success", true);
        if (record != null) {
            response.put("content", record.content());
            response.put("md5", record.md5());
            response.put("lastModified", record.lastModified());
            response.put("contentType", record.type());
        } else {
            response.put("resultCode", 302);
            response.put("message", "config data not exist");
        }
        return buildPayload(TYPE_CONFIG_QUERY_RESPONSE, response, clientIp);
    }

    private Payload handleConfigRemove(Payload payload, String clientIp) {
        JSONObject body = parseBody(payload);
        String dataId = body.getString("dataId");
        String group = body.getString("group");
        String tenant = body.getString("tenant");
        if (tenant == null || tenant.isEmpty()) {
            tenant = "public";
        }

        boolean ok = configStorage.removeConfig(tenant, dataId, group);

        // Trigger distro sync
        if (ok) {
            String key = tenant + "@@" + dataId + "@@" + group;
            distroProtocol.syncConfigChange(key, DistroProtocol.OP_DELETE, new byte[0]);
        }

        JSONObject response = new JSONObject();
        response.put("resultCode", ok ? 200 : 500);
        response.put("success", ok);
        return buildPayload(TYPE_CONFIG_REMOVE_RESPONSE, response, clientIp);
    }

    private Payload handleConfigBatchListen(Payload payload, String clientIp) {
        JSONObject body = parseBody(payload);
        boolean listen = body.getBooleanValue("listen", true);
        String connectionId = connectionIdByClientIp.get(clientIp);

        if (connectionId != null && body.containsKey("configListenContexts")) {
            var contexts = body.getJSONArray("configListenContexts");
            for (int i = 0; i < contexts.size(); i++) {
                JSONObject ctx = contexts.getJSONObject(i);
                String dataId = ctx.getString("dataId");
                String group = ctx.getString("group");
                String tenant = ctx.getString("tenant");
                if (tenant == null || tenant.isEmpty()) {
                    tenant = "public";
                }
                if (listen) {
                    configStorage.addListener(tenant, dataId, group, connectionId);
                } else {
                    configStorage.removeListener(tenant, dataId, group, connectionId);
                }
            }
        }

        JSONObject response = new JSONObject();
        response.put("resultCode", 200);
        response.put("success", true);
        return buildPayload(TYPE_CONFIG_BATCH_LISTEN_RESPONSE, response, clientIp);
    }

    // ========================================================================
    // Distro inter-node handlers
    // ========================================================================

    private Payload handleDistroSync(Payload payload) {
        JSONObject body = parseBody(payload);
        String resourceType = body.getString("resourceType");
        String resourceKey = body.getString("resourceKey");
        String operation = body.getString("operation");
        String contentStr = body.getString("content");
        byte[] content = (contentStr != null && !contentStr.isEmpty())
                ? java.util.Base64.getDecoder().decode(contentStr)
                : new byte[0];

        boolean ok = distroProtocol.onReceive(resourceType, resourceKey, operation, content);

        JSONObject response = new JSONObject();
        response.put("resultCode", ok ? 200 : 500);
        response.put("success", ok);
        return buildPayload(TYPE_DISTRO_SYNC_RESPONSE, response);
    }

    private Payload handleDistroVerify(Payload payload) {
        JSONObject body = parseBody(payload);
        String resourceType = body.getString("resourceType");
        JSONObject checksums = body.getJSONObject("checksums");
        if (checksums == null) {
            checksums = new JSONObject();
        }

        boolean ok = distroProtocol.onVerify(resourceType, checksums);

        JSONObject response = new JSONObject();
        response.put("resultCode", ok ? 200 : 500);
        response.put("success", ok);
        return buildPayload(TYPE_DISTRO_VERIFY_RESPONSE, response);
    }

    private Payload handleDistroSnapshot(Payload payload) {
        JSONObject body = parseBody(payload);
        String resourceType = body.getString("resourceType");

        byte[] snapshot = distroProtocol.onSnapshot(resourceType);

        JSONObject response = new JSONObject();
        response.put("resultCode", 200);
        response.put("success", true);
        response.put("resourceType", resourceType);
        response.put("content", snapshot != null
                ? java.util.Base64.getEncoder().encodeToString(snapshot) : "");
        return buildPayload(TYPE_DISTRO_SNAPSHOT_RESPONSE, response);
    }

    private Payload buildErrorResponse(String responseType, String message) {
        JSONObject body = new JSONObject();
        body.put("resultCode", 500);
        body.put("success", false);
        body.put("errorCode", 500);
        body.put("message", message);
        return buildPayload(responseType, body);
    }

    // ========================================================================
    // Subscriber notification (server push via BiStream)
    // ========================================================================

    private void notifySubscriber(String connectionId, String namespace, String group,
                                  String serviceName, JSONObject serviceInfo) {
        JSONObject body = new JSONObject();
        body.put("namespace", namespace);
        body.put("serviceName", serviceName);
        body.put("groupName", group);
        body.put("serviceInfo", serviceInfo);

        Payload pushPayload = buildPayload(TYPE_NOTIFY_SUBSCRIBER_REQUEST, body);
        boolean pushed = connectionManager.pushToConnection(connectionId, pushPayload);
        if (!pushed) {
            log.debug("[harbor] failed to push to connection {}: not found", connectionId);
        }
    }

    // ========================================================================
    // Config change notification (server push via BiStream)
    // ========================================================================

    private void notifyConfigListener(String connectionId, String namespace,
                                      String dataId, String group) {
        JSONObject body = new JSONObject();
        body.put("dataId", dataId);
        body.put("group", group);
        body.put("tenant", namespace);

        Payload pushPayload = buildPayload(TYPE_CONFIG_CHANGE_NOTIFY_REQUEST, body);
        boolean pushed = connectionManager.pushToConnection(connectionId, pushPayload);
        if (!pushed) {
            log.debug("[harbor] failed to push config notify to connection {}: not found",
                    connectionId);
        }
    }
}
