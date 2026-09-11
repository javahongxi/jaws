package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.channel.ChannelPipeline;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.distro.DistroConfig;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.GrpcHarborNodeTransport;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.Request;
import org.hongxi.jaws.harbor.model.Response;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.proto.Metadata;
import org.hongxi.jaws.harbor.proto.Payload;
import org.hongxi.jaws.harbor.model.request.*;
import org.hongxi.jaws.harbor.model.response.*;
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
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
 * Phase 2 adds the Distro protocol for multi-node replication of
 * service naming data.
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
    private static final String TYPE_SETUP_ACK_REQUEST = "SetupAckRequest";
    private static final String TYPE_INSTANCE_REQUEST = "InstanceRequest";
    private static final String TYPE_SUBSCRIBE_SERVICE_REQUEST = "SubscribeServiceRequest";
    private static final String TYPE_SERVICE_QUERY_REQUEST = "ServiceQueryRequest";
    private static final String TYPE_SERVICE_LIST_REQUEST = "ServiceListRequest";
    private static final String TYPE_NOTIFY_SUBSCRIBER_RESPONSE = "NotifySubscriberResponse";
    private static final String TYPE_HEALTH_CHECK_REQUEST = "HealthCheckRequest";

    // Config requests from nacos-client (not supported — return silent success)
    private static final String TYPE_CONFIG_BATCH_LISTEN_REQUEST = "ConfigBatchListenRequest";

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
    /**
     * Handlers waiting to be associated with a clientIp. Each new connection
     * enqueues a handler in addOptionalChannelHandlers; handleServerCheck
     * polls and claims it. This avoids any channel-ref passing.
     */
    private final Queue<ConnectionCleanupHandler> pendingCleanupHandlers =
            new ConcurrentLinkedQueue<>();

    public HarborServer(URL url) {
        this(url, new GrpcHarborNodeTransport());
    }

    public HarborServer(URL url, HarborNodeTransport transport) {
        this.serviceStorage = new ServiceStorage(this::notifySubscriber);
        this.healthCheckManager = new HealthCheckManager(this.serviceStorage, this.connectionManager);
        this.clusterManager = new ClusterManager();
        String selfAddr = resolveSelfAddress(url.getHost()) + ":" + url.getPort();
        this.clusterManager.setSelfAddress(selfAddr);

        DistroConfig distroConfig = new DistroConfig();
        this.distroProtocol = new DistroProtocol(clusterManager, distroConfig, transport,
                serviceStorage);

        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register(SERVICE_NAME_REQUEST, METHOD_REQUEST, new RequestHandler());
        registry.register(SERVICE_NAME_BI_STREAM, METHOD_BI_STREAM, new BiStreamHandler());

        this.wireServer = new WireServer(url, registry) {
            @Override
            protected void addOptionalChannelHandlers(ChannelPipeline pipeline) {
                super.addOptionalChannelHandlers(pipeline);
                // Close the connection on incoming GOAWAY and clean up connection
                // state when the channel becomes inactive. Without this, a graceful
                // client shutdown (GOAWAY) would leave the connection registered
                // until the 90-second watchdog fires.
                ConnectionCleanupHandler handler = new ConnectionCleanupHandler(HarborServer.this);
                pipeline.addLast("conn_cleanup", handler);
                pendingCleanupHandlers.add(handler);
            }
        };
    }

    /**
     * Clean up connection state by connectionId. Called by {@link ConnectionCleanupHandler}
     * when the connection channel becomes inactive (client GOAWAY, network failure, etc.).
     * Deregisters instances and removes subscribers.
     */
    void cleanupConnectionById(String connId) {
        connectionManager.remove(connId);
        serviceStorage.removeAllSubscribersForConnection(connId);
        int removed = serviceStorage.deregisterInstancesByConnectionId(connId);
        if (removed > 0) {
            log.info("[harbor] deregistered {} instance(s) on connection close: connId={}",
                    removed, connId);
        }
        // Also clean up the clientIp mapping if it points to this connection
        connectionIdByClientIp.values().removeIf(connId::equals);
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

    /**
     * Deserialize the Payload body into a typed request object.
     */
    static <T extends Request> T parseBody(Payload payload, Class<T> clazz) {
        byte[] bytes = payload.getBody().getValue().toByteArray();
        if (bytes.length == 0) {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to create empty " + clazz.getSimpleName(), e);
            }
        }
        return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8), clazz);
    }

    /**
     * Build a Payload envelope wrapping a typed response object.
     */
    static Payload buildPayload(String type, Response response) {
        byte[] jsonBytes = JSON.toJSONBytes(response);
        return Payload.newBuilder()
                .setMetadata(Metadata.newBuilder()
                        .setType(type)
                        .build())
                .setBody(Any.newBuilder()
                        .setValue(ByteString.copyFrom(jsonBytes))
                        .build())
                .build();
    }

    /**
     * Build a Payload envelope wrapping a typed response object with clientIp.
     */
    static Payload buildPayload(String type, Response response, String clientIp) {
        byte[] jsonBytes = JSON.toJSONBytes(response);
        return Payload.newBuilder()
                .setMetadata(Metadata.newBuilder()
                        .setType(type)
                        .setClientIp(clientIp)
                        .build())
                .setBody(Any.newBuilder()
                        .setValue(ByteString.copyFrom(jsonBytes))
                        .build())
                .build();
    }

    /**
     * Build a Payload envelope wrapping a typed push request object.
     */
    static Payload buildPushPayload(String type, Request pushRequest) {
        byte[] jsonBytes = JSON.toJSONBytes(pushRequest);
        return Payload.newBuilder()
                .setMetadata(Metadata.newBuilder()
                        .setType(type)
                        .build())
                .setBody(Any.newBuilder()
                        .setValue(ByteString.copyFrom(jsonBytes))
                        .build())
                .build();
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

            // Touch the connection so the stale-connection watchdog knows it's alive
            String connId = connectionIdByClientIp.get(clientIp);
            if (connId != null) {
                connectionManager.touch(connId);
            }

            try {
                return switch (type) {
                    // Naming
                    case TYPE_SERVER_CHECK_REQUEST -> handleServerCheck(clientIp);
                    case TYPE_INSTANCE_REQUEST -> handleInstanceRequest(payload, clientIp);
                    case TYPE_SUBSCRIBE_SERVICE_REQUEST -> handleSubscribe(payload, clientIp);
                    case TYPE_SERVICE_QUERY_REQUEST -> handleServiceQuery(payload);
                    case TYPE_SERVICE_LIST_REQUEST -> handleServiceList(payload);
                    case TYPE_HEALTH_CHECK_REQUEST -> handleHealthCheck();
                    // Distro inter-node
                    case TYPE_DISTRO_SYNC_REQUEST -> handleDistroSync(payload);
                    case TYPE_DISTRO_VERIFY_REQUEST -> handleDistroVerify(payload);
                    case TYPE_DISTRO_SNAPSHOT_REQUEST -> handleDistroSnapshot(payload);
                    // Config requests — Harbor does not support config center;
                    // return silent success to prevent nacos-client from retrying.
                    case TYPE_CONFIG_BATCH_LISTEN_REQUEST ->
                            buildPayload("ConfigBatchListenResponse",
                                    org.hongxi.jaws.harbor.model.response.ConfigBatchListenResponse.ok());
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
                private String clientIp;

                @Override
                public void onNext(Message item) {
                    if (!(item instanceof Payload payload)) {
                        return;
                    }
                    String type = payload.getMetadata().getType();
                    String ip = payload.getMetadata().getClientIp();

                    switch (type) {
                        case TYPE_CONNECTION_SETUP_REQUEST -> {
                            ConnectionSetupRequest setup = parseBody(payload, ConnectionSetupRequest.class);
                            // Look up the connectionId assigned during ServerCheck
                            connectionId = connectionIdByClientIp.get(ip);
                            if (connectionId == null) {
                                connectionId = UUID.randomUUID().toString();
                            }
                            this.clientIp = ip;
                            String version = setup.getClientVersion();
                            Map<String, String> labels = setup.getLabels();
                            if (labels == null) {
                                labels = Map.of();
                            }
                            connectionManager.register(connectionId, ip, version,
                                    labels, pushSubject);
                            // Always send SetupAckRequest back through the bi-stream.
                            // The nacos-client expects this ack to confirm the connection
                            // is established. Not sending it causes the client to think
                            // the connection is bad and trigger GOAWAY → reconnect loop.
                            // The nacos-client bi-stream observer casts every incoming
                            // payload to Request, NOT Response. Sending a Response subclass
                            // causes ClassCastException → onError → switchServerAsync →
                            // infinite reconnection loop (~10s cycle).
                            Payload setupAck = buildPushPayload(TYPE_SETUP_ACK_REQUEST,
                                    new SetupAckRequest(Map.of()));
                            log.debug("[harbor] sending SetupAckRequest to connId={}, clientIp={}",
                                    connectionId, ip);
                            pushSubject.onNext(setupAck);
                        }
                        case TYPE_NOTIFY_SUBSCRIBER_RESPONSE ->
                            // Client ack for a NotifySubscriberRequest — no action needed
                                log.debug("[harbor] received NotifySubscriberResponse ack");
                        default -> log.debug("[harbor] bi-stream received type={}", type);
                    }
                    // Touch the connection on every inbound bi-stream message
                    if (connectionId != null) {
                        connectionManager.touch(connectionId);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.info("[harbor] bi-stream error: {}", throwable.getMessage());
                    if (connectionId != null) {
                        cleanupConnection(connectionId);
                    }
                    // Do NOT call pushSubject.onCompleted() here.
                    // The client already sent RST/GOAWAY — the stream is dead.
                    // Sending trailers on a reset stream confuses the Nacos
                    // client SDK and triggers an immediate reconnect cycle.
                    // channelInactive will complete pushSubject when the TCP
                    // connection actually closes.
                }

                @Override
                public void onCompleted() {
                    log.info("[harbor] bi-stream completed for connId={}", connectionId);
                    if (connectionId != null) {
                        cleanupConnection(connectionId);
                    }
                    // Do NOT call pushSubject.onCompleted() — same reason as
                    // onError: the client initiated the close, the stream is
                    // already gone. channelInactive handles the final cleanup.
                }

                private void cleanupConnection(String connId) {
                    connectionManager.remove(connId);
                    serviceStorage.removeAllSubscribersForConnection(connId);
                    // Nacos 2.x connection-based health check model:
                    // when the bi-stream closes, the client is gone — deregister
                    // all instances registered by THIS connection (not by clientIp,
                    // which would also remove instances from other connections on
                    // the same machine, e.g. a co-located provider).
                    int removed = serviceStorage.deregisterInstancesByConnectionId(connId);
                    if (removed > 0) {
                        log.info("[harbor] deregistered {} instance(s) on disconnect for connId={}",
                                removed, connId);
                    }
                    if (clientIp != null) {
                        connectionIdByClientIp.remove(clientIp);
                    }
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
        // Claim the cleanup handler for this connection and set the clientIp
        // AND connectionId so that channelInactive can deregister instances
        // using the exact connectionId (not the shared map, which may have
        // been overwritten by another connection from the same clientIp).
        ConnectionCleanupHandler handler = pendingCleanupHandlers.poll();
        if (handler != null) {
            handler.setClientIp(clientIp);
            handler.setConnectionId(connectionId);
        }
        ServerCheckResponse response = new ServerCheckResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setConnectionId(connectionId);
        response.setSupportAbilityNegotiation(false);
        return buildPayload(TYPE_SERVER_CHECK_RESPONSE, response, clientIp);
    }

    private Payload handleInstanceRequest(Payload payload, String clientIp) {
        InstanceRequest request = parseBody(payload, InstanceRequest.class);
        String namespace = request.getNamespace();
        String serviceName = request.getServiceName();
        String groupName = request.getGroupName();
        String type = request.getType();

        Instance instance = request.getInstance();
        if (instance == null) {
            return buildErrorResponse(TYPE_INSTANCE_RESPONSE, "Missing instance");
        }

        // Set default instanceId if not provided
        if (instance.getInstanceId() == null || instance.getInstanceId().isEmpty()) {
            String groupedName = groupName + "@@" + serviceName;
            instance.setInstanceId(
                    instance.getIp() + "#" + instance.getPort()
                            + "#" + groupedName);
        }

        if (REGISTER_INSTANCE.equals(type)) {
            String connId = connectionIdByClientIp.get(clientIp);
            serviceStorage.registerInstance(namespace, groupName, serviceName, instance, connId);
            // Trigger distro sync to peers
            String key = namespace + "@@" + groupName + "@@" + serviceName;
            byte[] syncContent = JSON.toJSONBytes(instance);
            distroProtocol.syncNamingChange(key, DistroProtocol.OP_CHANGE, syncContent);
        } else if (DE_REGISTER_INSTANCE.equals(type)) {
            serviceStorage.deregisterInstance(namespace, groupName, serviceName, instance);
            String key = namespace + "@@" + groupName + "@@" + serviceName;
            distroProtocol.syncNamingChange(key, DistroProtocol.OP_DELETE, new byte[0]);
        } else {
            return buildErrorResponse(TYPE_INSTANCE_RESPONSE,
                    "Unknown instance operation type: " + type);
        }

        InstanceResponse response = new InstanceResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setType(type);
        return buildPayload(TYPE_INSTANCE_RESPONSE, response, clientIp);
    }

    private Payload handleSubscribe(Payload payload, String clientIp) {
        SubscribeServiceRequest request = parseBody(payload, SubscribeServiceRequest.class);
        String namespace = request.getNamespace();
        String serviceName = request.getServiceName();
        String groupName = request.getGroupName();
        boolean subscribe = request.isSubscribe();

        // Find the connectionId for this client
        String connectionId = connectionIdByClientIp.get(clientIp);
        if (connectionId != null && subscribe) {
            serviceStorage.addSubscriber(namespace, groupName, serviceName, connectionId);
        } else if (connectionId != null) {
            serviceStorage.removeSubscriber(namespace, groupName, serviceName, connectionId);
        }

        ServiceInfo serviceInfo = serviceStorage.buildServiceInfo(
                namespace, groupName, serviceName);

        ServiceInfoResponse response = new ServiceInfoResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setServiceInfo(serviceInfo);
        return buildPayload(TYPE_SUBSCRIBE_SERVICE_RESPONSE, response, clientIp);
    }

    private Payload handleServiceQuery(Payload payload) {
        ServiceQueryRequest request = parseBody(payload, ServiceQueryRequest.class);
        String namespace = request.getNamespace();
        String serviceName = request.getServiceName();
        String groupName = request.getGroupName();

        ServiceInfo serviceInfo = serviceStorage.buildServiceInfo(
                namespace, groupName, serviceName);

        ServiceInfoResponse response = new ServiceInfoResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setServiceInfo(serviceInfo);
        return buildPayload(TYPE_QUERY_SERVICE_RESPONSE, response);
    }

    private Payload handleServiceList(Payload payload) {
        ServiceListRequest request = parseBody(payload, ServiceListRequest.class);
        String namespace = request.getNamespace();
        String groupName = request.getGroupName();
        if (groupName == null || groupName.isEmpty()) {
            groupName = "DEFAULT_GROUP";
        }

        java.util.List<String> services = serviceStorage.listServices(namespace, groupName);

        ServiceListResponse response = new ServiceListResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setCount(services.size());
        response.setServiceNames(services);
        return buildPayload(TYPE_SERVICE_LIST_RESPONSE, response);
    }

    private Payload handleHealthCheck() {
        HealthCheckResponse response = new HealthCheckResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setStatus("SERVING");
        return buildPayload(TYPE_HEALTH_CHECK_RESPONSE, response);
    }

    // ========================================================================
    // Distro inter-node handlers
    // ========================================================================

    private Payload handleDistroSync(Payload payload) {
        DistroSyncRequest request = parseBody(payload, DistroSyncRequest.class);
        String resourceType = request.getResourceType();
        String resourceKey = request.getResourceKey();
        String operation = request.getOperation();
        String contentStr = request.getContent();
        byte[] content = (contentStr != null && !contentStr.isEmpty())
                ? java.util.Base64.getDecoder().decode(contentStr)
                : new byte[0];

        boolean ok = distroProtocol.onReceive(resourceType, resourceKey, operation, content);

        DistroSyncResponse response = new DistroSyncResponse();
        response.setResultCode(ok ? 200 : 500);
        response.setSuccess(ok);
        return buildPayload(TYPE_DISTRO_SYNC_RESPONSE, response);
    }

    private Payload handleDistroVerify(Payload payload) {
        DistroVerifyRequest request = parseBody(payload, DistroVerifyRequest.class);
        String resourceType = request.getResourceType();
        Map<String, String> checksums = request.getChecksums();
        if (checksums == null) {
            checksums = Map.of();
        }

        boolean ok = distroProtocol.onVerify(resourceType, checksums);

        DistroVerifyResponse response = new DistroVerifyResponse();
        response.setResultCode(ok ? 200 : 500);
        response.setSuccess(ok);
        return buildPayload(TYPE_DISTRO_VERIFY_RESPONSE, response);
    }

    private Payload handleDistroSnapshot(Payload payload) {
        DistroSnapshotRequest request = parseBody(payload, DistroSnapshotRequest.class);
        String resourceType = request.getResourceType();

        byte[] snapshot = distroProtocol.onSnapshot(resourceType);

        DistroSnapshotResponse response = new DistroSnapshotResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setResourceType(resourceType);
        response.setContent(snapshot != null
                ? java.util.Base64.getEncoder().encodeToString(snapshot) : "");
        return buildPayload(TYPE_DISTRO_SNAPSHOT_RESPONSE, response);
    }

    private Payload buildErrorResponse(String responseType, String message) {
        Response response = Response.error(500);
        byte[] jsonBytes = JSON.toJSONBytes(new ErrorResponse(message));
        return Payload.newBuilder()
                .setMetadata(Metadata.newBuilder()
                        .setType(responseType)
                        .build())
                .setBody(Any.newBuilder()
                        .setValue(ByteString.copyFrom(jsonBytes))
                        .build())
                .build();
    }

    // ========================================================================
    // Subscriber notification (server push via BiStream)
    // ========================================================================

    private void notifySubscriber(String connectionId, String namespace, String group,
                                  String serviceName, ServiceInfo serviceInfo) {
        NotifySubscriberRequest push = new NotifySubscriberRequest();
        push.setNamespace(namespace);
        push.setServiceName(serviceName);
        push.setGroupName(group);
        push.setServiceInfo(serviceInfo);

        Payload pushPayload = buildPushPayload(TYPE_NOTIFY_SUBSCRIBER_REQUEST, push);
        boolean pushed = connectionManager.pushToConnection(connectionId, pushPayload);
        if (!pushed) {
            log.debug("[harbor] failed to push to connection {}: not found", connectionId);
        }
    }

    // ========================================================================
    // Internal helper types
    // ========================================================================

    /**
     * Internal DTO for error responses that need an errorCode and message.
     */
    static class ErrorResponse {
        private int resultCode = 500;
        private boolean success = false;
        private int errorCode = 500;
        private String message;

        ErrorResponse() {}

        ErrorResponse(String message) {
            this.message = message;
        }

        public int getResultCode() { return resultCode; }
        public boolean isSuccess() { return success; }
        public int getErrorCode() { return errorCode; }
        public String getMessage() { return message; }
    }
}
