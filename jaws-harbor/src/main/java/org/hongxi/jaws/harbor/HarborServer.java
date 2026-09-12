package org.hongxi.jaws.harbor;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.GrpcHarborNodeTransport;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientSyncData;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
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
import org.hongxi.jaws.wire.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    // Nacos naming request and response types
    private static final String TYPE_SERVER_CHECK_REQUEST = "ServerCheckRequest";
    private static final String TYPE_SERVER_CHECK_RESPONSE = "ServerCheckResponse";
    private static final String TYPE_INSTANCE_REQUEST = "InstanceRequest";
    private static final String TYPE_BATCH_INSTANCE_REQUEST = "BatchInstanceRequest";
    private static final String TYPE_INSTANCE_RESPONSE = "InstanceResponse";
    private static final String TYPE_SUBSCRIBE_SERVICE_REQUEST = "SubscribeServiceRequest";
    private static final String TYPE_SUBSCRIBE_SERVICE_RESPONSE = "SubscribeServiceResponse";
    private static final String TYPE_SERVICE_QUERY_REQUEST = "ServiceQueryRequest";
    private static final String TYPE_QUERY_SERVICE_RESPONSE = "QueryServiceResponse";
    private static final String TYPE_SERVICE_LIST_REQUEST = "ServiceListRequest";
    private static final String TYPE_SERVICE_LIST_RESPONSE = "ServiceListResponse";
    private static final String TYPE_HEALTH_CHECK_REQUEST = "HealthCheckRequest";
    private static final String TYPE_HEALTH_CHECK_RESPONSE = "HealthCheckResponse";

    // Nacos naming bidi request types
    private static final String TYPE_CONNECTION_SETUP_REQUEST = "ConnectionSetupRequest";
    private static final String TYPE_SETUP_ACK_REQUEST = "SetupAckRequest";
    private static final String TYPE_NOTIFY_SUBSCRIBER_REQUEST = "NotifySubscriberRequest";
    private static final String TYPE_NOTIFY_SUBSCRIBER_RESPONSE = "NotifySubscriberResponse";

    // Nacos naming instance request types
    private static final String REGISTER_INSTANCE = "registerInstance";
    private static final String DEREGISTER_INSTANCE = "deregisterInstance";

    // Config requests from nacos-client (not supported — return silent success)
    private static final String TYPE_CONFIG_BATCH_LISTEN_REQUEST = "ConfigBatchListenRequest";

    // Distro inter-node request and response types
    private static final String TYPE_DISTRO_SYNC_REQUEST = "DistroSyncRequest";
    private static final String TYPE_DISTRO_SYNC_RESPONSE = "DistroSyncResponse";
    private static final String TYPE_DISTRO_VERIFY_REQUEST = "DistroVerifyRequest";
    private static final String TYPE_DISTRO_VERIFY_RESPONSE = "DistroVerifyResponse";
    private static final String TYPE_DISTRO_SNAPSHOT_REQUEST = "DistroSnapshotRequest";
    private static final String TYPE_DISTRO_SNAPSHOT_RESPONSE = "DistroSnapshotResponse";

    /**
     * URL parameter for specifying initial cluster members.
     * Format: comma-separated {@code host:port} pairs,
     * e.g. {@code "10.0.0.1:19848,10.0.0.2:19848"}.
     */
    public static final String PARAM_CLUSTER_MEMBERS = "clusterMembers";

    private final ServiceStorage serviceStorage;
    private final ConnectionManager connectionManager;
    private final HealthCheckManager healthCheckManager;
    private final ClusterManager clusterManager;
    private final DistroProtocol distroProtocol;
    private final PushRetryManager pushRetryManager;
    private final WireServer wireServer;

    private HarborHttpApi httpApi;

    public HarborServer(URL url) {
        this(url, new GrpcHarborNodeTransport());
    }

    public HarborServer(URL url, HarborNodeTransport transport) {
        this.connectionManager = new ConnectionManager();
        this.serviceStorage = new ServiceStorage(this::notifySubscriber, this.connectionManager);
        this.healthCheckManager = new HealthCheckManager(this.serviceStorage, this.connectionManager);
        this.pushRetryManager = new PushRetryManager(this.connectionManager);

        this.clusterManager = new ClusterManager(url);
        this.distroProtocol = new DistroProtocol(clusterManager, transport, serviceStorage, connectionManager);

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
                // Generate a unique connectionId for this TCP connection (parent channel)
                // and store it as a channel attribute.  The wire layer reads this attribute
                // and injects it into WireCallContext so that every handler on this
                // connection can identify which physical connection a request arrived on.
                // This fixes the bug where connectionIdByClientIp was overwritten when
                // multiple processes from the same IP connected simultaneously.
                AttributeKey<String> key = AttributeKey.valueOf(WireConstants.CONNECTION_ID);
                String connectionId = pipeline.channel().attr(key).get();
                if (connectionId == null) {
                    connectionId = UUID.randomUUID().toString();
                    pipeline.channel().attr(key).set(connectionId);
                }
                ConnectionCleanupHandler handler = new ConnectionCleanupHandler(HarborServer.this);
                handler.setConnectionId(connectionId);
                pipeline.addLast("conn_cleanup", handler);
            }
        };
    }

    public void start() {
        // Register self as a cluster member
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

        // Start HTTP/1.1 management API (port from URL param, default grpcPort + 10)
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
        pushRetryManager.shutdown();
        healthCheckManager.shutdown();
        distroProtocol.shutdown();
        wireServer.close();
        log.info("[harbor] server closed");
    }

    public ServiceStorage getServiceStorage() {
        return serviceStorage;
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
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
     * Clean up connection state by connectionId. Called by {@link ConnectionCleanupHandler}
     * when the connection channel becomes inactive (client GOAWAY, network failure, etc.).
     * Deregisters instances and removes subscribers.
     */
    void cleanupConnectionById(String connId) {
        // Capture client data before removal for Distro DELETE sync
        ClientSyncData syncData = serviceStorage.buildClientSyncData(connId);

        connectionManager.remove(connId);
        serviceStorage.removeAllSubscribersForConnection(connId);
        int removed = serviceStorage.deregisterInstancesByConnectionId(connId);
        if (removed > 0) {
            log.info("[harbor] deregistered {} instance(s) on connection close: connId={}",
                    removed, connId);
        }
        // Notify peers that this client is gone
        if (syncData != null && !syncData.getServiceKeys().isEmpty()) {
            distroProtocol.syncChange(connId, DistroProtocol.OP_DELETE, new byte[0]);
        }
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

    /**
     * Build an error response
     */
    static Payload buildErrorResponse(String responseType, String message) {
        record ErrorResponse(String message) {}
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

            // Resolve the connectionId from the wire call context (injected by
            // the wire layer from the parent channel attribute).  This is the
            // per-TCP-connection unique ID, safe even when multiple processes
            // from the same clientIp connect simultaneously.
            String connectionId = context != null
                    ? context.getAttachment(WireConstants.CONNECTION_ID)
                    : null;

            // Update heartbeat for all instances from this connection (Nacos connection-based model)
            serviceStorage.updateHeartbeatByConnectionId(connectionId);

            // Touch the specific connection if identified, otherwise fall back
            // to touching ALL connections from this client IP.
            if (connectionId != null) {
                connectionManager.touch(connectionId);
            } else {
                connectionManager.touchByClientIp(clientIp);
            }

            try {
                return switch (type) {
                    // Naming
                    case TYPE_SERVER_CHECK_REQUEST -> handleServerCheck(clientIp, connectionId);
                    case TYPE_INSTANCE_REQUEST -> handleInstanceRequest(payload, clientIp, connectionId);
                    case TYPE_BATCH_INSTANCE_REQUEST -> handleBatchInstanceRequest(payload, clientIp, connectionId);
                    case TYPE_SUBSCRIBE_SERVICE_REQUEST -> handleSubscribe(payload, clientIp, connectionId);
                    case TYPE_SERVICE_QUERY_REQUEST -> handleServiceQuery(payload);
                    case TYPE_SERVICE_LIST_REQUEST -> handleServiceList(payload);
                    case TYPE_HEALTH_CHECK_REQUEST -> handleHealthCheck();
                    // Distro inter-node
                    case TYPE_DISTRO_SYNC_REQUEST -> handleDistroSync(payload);
                    case TYPE_DISTRO_VERIFY_REQUEST -> handleDistroVerify(payload);
                    case TYPE_DISTRO_SNAPSHOT_REQUEST -> handleDistroSnapshot();
                    // Config requests — Harbor does not support config center;
                    // return silent success to prevent nacos-client from retrying.
                    case TYPE_CONFIG_BATCH_LISTEN_REQUEST ->
                            buildPayload("ConfigBatchListenResponse", ConfigBatchListenResponse.ok());
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
        public StreamSource<Message> handleBidiStream(StreamSource<Message> requestStream) {
            return handleBidiStream(requestStream, null);
        }

        @Override
        public StreamSource<Message> handleBidiStream(StreamSource<Message> requestStream,
                                                      WireCallContext context) {
            // Create a push subject for server→client notifications
            StreamSubject<Message> pushSubject = new StreamSubject<>();

            // Resolve the connectionId from the parent channel attribute
            // (propagated via WireCallContext by the wire layer).
            String initialConnectionId = context != null
                    ? context.getAttachment(WireConstants.CONNECTION_ID)
                    : null;

            // Process incoming client messages (ConnectionSetupRequest, acks, etc.)
            requestStream.subscribe(new StreamObserver<>() {
                private String connectionId = initialConnectionId;

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
                            // connectionId is already set from the parent channel attribute
                            // (captured in the field initializer).  Fall back only if null.
                            if (connectionId == null) {
                                connectionId = UUID.randomUUID().toString();
                            }
                            String version = setup.getClientVersion();
                            Map<String, String> labels = setup.getLabels();
                            if (labels == null) {
                                labels = Map.of();
                            }
                            connectionManager.register(connectionId, ip, version, labels, pushSubject);
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
                    // Capture client data before removal for Distro DELETE sync
                    ClientSyncData syncData = serviceStorage.buildClientSyncData(connId);

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
                    // Notify peers that this client is gone
                    if (syncData != null && !syncData.getServiceKeys().isEmpty()) {
                        distroProtocol.syncChange(connId, DistroProtocol.OP_DELETE, new byte[0]);
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

    private Payload handleServerCheck(String clientIp, String connectionId) {
        // connectionId is generated in addOptionalChannelHandlers (per TCP
        // connection) and propagated via the parent channel attribute →
        // WireCallContext.  If not yet available (should not happen), fall
        // back to generating one here.
        if (connectionId == null) {
            connectionId = UUID.randomUUID().toString();
        }
        ServerCheckResponse response = new ServerCheckResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setConnectionId(connectionId);
        response.setSupportAbilityNegotiation(false);
        return buildPayload(TYPE_SERVER_CHECK_RESPONSE, response, clientIp);
    }

    private Payload handleInstanceRequest(Payload payload, String clientIp, String connectionId) {
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
            instance.setInstanceId(instance.getIp() + "#" + instance.getPort() + "#" + groupedName);
        }

        if (REGISTER_INSTANCE.equals(type)) {
            serviceStorage.registerInstance(namespace, groupName, serviceName, instance, connectionId);
            // Sync full client state to peers (client-level granularity)
            syncClientDataToPeers(connectionId);
        } else if (DEREGISTER_INSTANCE.equals(type)) {
            serviceStorage.deregisterInstance(namespace, groupName, serviceName, instance, connectionId);
            // Deregister is also a CHANGE (full client state replacement), not DELETE.
            // DELETE is only used when the entire connection goes away.
            syncClientDataToPeers(connectionId);
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

    private Payload handleBatchInstanceRequest(Payload payload, String clientIp, String connectionId) {
        BatchInstanceRequest request = parseBody(payload, BatchInstanceRequest.class);
        String namespace = request.getNamespace();
        String serviceName = request.getServiceName();
        String groupName = request.getGroupName();

        List<Instance> instances = request.getInstances();
        if (instances == null || instances.isEmpty()) {
            return buildErrorResponse(TYPE_INSTANCE_RESPONSE, "Missing instances");
        }

        for (Instance instance : instances) {
            // Set default instanceId if not provided
            if (instance.getInstanceId() == null || instance.getInstanceId().isEmpty()) {
                String groupedName = groupName + "@@" + serviceName;
                instance.setInstanceId(instance.getIp() + "#" + instance.getPort() + "#" + groupedName);
            }
            serviceStorage.registerInstance(namespace, groupName, serviceName, instance, connectionId);
        }

        // Sync full client state to peers after batch registration
        syncClientDataToPeers(connectionId);

        InstanceResponse response = new InstanceResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setType(request.getType());
        return buildPayload(TYPE_INSTANCE_RESPONSE, response, clientIp);
    }

    private Payload handleSubscribe(Payload payload, String clientIp, String connectionId) {
        SubscribeServiceRequest request = parseBody(payload, SubscribeServiceRequest.class);
        String namespace = request.getNamespace();
        String serviceName = request.getServiceName();
        String groupName = request.getGroupName();
        boolean subscribe = request.isSubscribe();

        // connectionId is propagated from the wire layer (parent channel attribute)
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
        String resourceKey = request.getResourceKey();
        String operation = request.getOperation();
        String contentStr = request.getContent();
        byte[] content = (contentStr != null && !contentStr.isEmpty())
                ? Base64.getDecoder().decode(contentStr)
                : new byte[0];

        boolean ok = distroProtocol.onSync(resourceKey, operation, content);

        DistroSyncResponse response = new DistroSyncResponse();
        response.setResultCode(ok ? 200 : 500);
        response.setSuccess(ok);
        return buildPayload(TYPE_DISTRO_SYNC_RESPONSE, response);
    }

    private Payload handleDistroVerify(Payload payload) {
        DistroVerifyRequest request = parseBody(payload, DistroVerifyRequest.class);
        List<ClientVerifyInfo> verifyInfos = request.getVerifyInfos();
        if (verifyInfos == null) {
            verifyInfos = List.of();
        }

        List<String> mismatched = distroProtocol.onVerify(verifyInfos);

        DistroVerifyResponse response = new DistroVerifyResponse();
        if (mismatched.isEmpty()) {
            response.setResultCode(200);
            response.setSuccess(true);
        } else {
            response.setResultCode(500);
            response.setSuccess(false);
            response.setMismatchedClientIds(mismatched);
        }
        return buildPayload(TYPE_DISTRO_VERIFY_RESPONSE, response);
    }

    private Payload handleDistroSnapshot() {
        byte[] snapshot = distroProtocol.onSnapshot();

        DistroSnapshotResponse response = new DistroSnapshotResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setContent(snapshot != null
                ? Base64.getEncoder().encodeToString(snapshot) : "");
        return buildPayload(TYPE_DISTRO_SNAPSHOT_RESPONSE, response);
    }

    /**
     * Build the full ClientSyncData for the given connection and sync to peers.
     */
    private void syncClientDataToPeers(String connId) {
        if (connId == null) {
            return;
        }
        ClientSyncData syncData = serviceStorage.buildClientSyncData(connId);
        if (syncData != null) {
            byte[] content = JSON.toJSONBytes(syncData);
            distroProtocol.syncChange(connId, DistroProtocol.OP_CHANGE, content);
        }
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
            log.debug("[harbor] failed to push to connection {}, scheduling retry", connectionId);
            pushRetryManager.scheduleRetry(connectionId, namespace, group, serviceName, serviceInfo, 1);
        }
    }
}
