package org.hongxi.jaws.harbor;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import org.hongxi.jaws.harbor.cluster.ClusterManager;
import org.hongxi.jaws.harbor.cluster.ClusterMember;
import org.hongxi.jaws.harbor.distro.DistroProtocol;
import org.hongxi.jaws.harbor.distro.WireHarborNodeTransport;
import org.hongxi.jaws.harbor.distro.HarborNodeTransport;
import org.hongxi.jaws.harbor.model.ClientVerifyInfo;
import org.hongxi.jaws.harbor.model.Instance;
import org.hongxi.jaws.harbor.model.ServiceInfo;
import org.hongxi.jaws.harbor.model.ServiceKey;
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

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jaws Harbor — a Nacos-compatible service registry server.
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
 * Multi-node replication of service naming data runs over the Distro AP protocol
 * (see {@code doc/harbor-vs-nacos.md}).
 *
 * @author shenhongxi
 */
public class HarborServer {

    private static final Logger log = LoggerFactory.getLogger(HarborServer.class);

    /**
     * URL parameter for specifying initial cluster members.
     * Format: comma-separated {@code host:port} pairs,
     * e.g. {@code "10.0.0.1:19848,10.0.0.2:19848"}.
     */
    public static final String PARAM_CLUSTER_MEMBERS = "clusterMembers";

    private final ServiceStorage serviceStorage;
    private final ConnectionManager connectionManager;
    private final ClusterManager clusterManager;
    private final DistroProtocol distroProtocol;
    private final ConnectionCleanup connectionCleanup;
    private final HealthCheckScheduler healthCheckScheduler;
    private final PushDelayTaskEngine pushEngine;
    private final WireServer wireServer;

    /** Unary dispatch table, keyed by the wire token of each request DTO. */
    private final Map<String, UnaryRequestHandler> unaryHandlers;

    private HarborHttpApi httpApi;

    public HarborServer(URL url) {
        this(url, new WireHarborNodeTransport());
    }

    public HarborServer(URL url, HarborNodeTransport transport) {
        this.connectionManager = new ConnectionManager();
        // Health verdicts are replicated data: the node that judges one must
        // re-publish the client, so reuse the coalesced outbound sync path that
        // registration changes already take. (distroProtocol is assigned below;
        // the hook only fires once the server is serving.)
        this.serviceStorage = new ServiceStorage(this.connectionManager, this::onServiceChange,
                this::syncClientDataToPeers);
        this.pushEngine = new PushDelayTaskEngine(this.serviceStorage, this.connectionManager);

        this.clusterManager = new ClusterManager(url);
        this.distroProtocol = new DistroProtocol(
                clusterManager, transport, serviceStorage, connectionManager);

        // The single connection-closure transaction, shared by the three
        // closure signals: DisconnectionHandler (channelInactive), the
        // bi-stream onError/onCompleted callbacks, and the watchdog sweep.
        this.connectionCleanup = new ConnectionCleanup(
                this.connectionManager, this.serviceStorage, this.distroProtocol);
        this.healthCheckScheduler = new HealthCheckScheduler(
                this.connectionManager, this.serviceStorage, this.connectionCleanup);

        this.unaryHandlers = buildUnaryHandlers();

        WireHandlerRegistry registry = new WireHandlerRegistry();
        registry.register(HarborProtocol.RPC_UNARY_SERVICE, HarborProtocol.RPC_UNARY_METHOD,
                new RequestHandler());
        registry.register(HarborProtocol.RPC_STREAM_SERVICE, HarborProtocol.RPC_STREAM_METHOD,
                new BiStreamHandler());

        this.wireServer = new WireServer(url, registry) {
            @Override
            protected void addOptionalChannelHandlers(ChannelPipeline pipeline) {
                super.addOptionalChannelHandlers(pipeline);
                // Close the connection on incoming GOAWAY and clean up connection
                // state when the channel becomes inactive. Without this, a graceful
                // client shutdown (GOAWAY) would leave the connection registered
                // until the 90-second watchdog fires.
                // Generate a unique connectionId for this TCP connection (parent channel)
                // and store it as a channel attribute.  The wire layer propagates this
                // attribute into every call context so that handlers on this connection
                // can identify which physical connection a request arrived on.
                AttributeKey<String> key = AttributeKey.valueOf(WireConstants.CONNECTION_ID);
                String connectionId = pipeline.channel().attr(key).get();
                if (connectionId == null) {
                    connectionId = UUID.randomUUID().toString();
                    pipeline.channel().attr(key).set(connectionId);
                }
                DisconnectionHandler handler =
                        new DisconnectionHandler(connectionCleanup, connectionId);
                pipeline.addLast("disconnection", handler);
            }
        };
        // Tell the wire layer to propagate the CONNECTION_ID attribute from the
        // parent (TCP) channel into every call context (WireCallContext / request
        // attachments), so that business handlers can access it without reaching
        // into the Netty pipeline.
        this.wireServer.addConnectionAttributeKey(WireConstants.CONNECTION_ID);
    }

    public void start() {
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
        healthCheckScheduler.start();

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
        // Stop ingress FIRST, the services it feeds LAST: tearing down connections
        // still notifies subscribers and still deletes clients over Distro, so a push
        // engine or a distro protocol closed underneath them throws back into a
        // Netty worker thread (observed as RejectedExecutionException on close).
        wireServer.close();
        healthCheckScheduler.shutdown();
        distroProtocol.shutdown();
        pushEngine.shutdown();
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

    // ========================================================================
    // Request.request handler (unary)
    // ========================================================================

    /** A single unary operation, given its envelope and the caller's identity. */
    @FunctionalInterface
    private interface UnaryRequestHandler {
        Payload handle(Payload payload, String clientIp, String connectionId);
    }

    /**
     * The whole unary contract in one table: every request DTO this server
     * answers, keyed by its wire token. Adding an operation means adding a DTO
     * and a line here, so a token can never disagree with a class name.
     */
    private Map<String, UnaryRequestHandler> buildUnaryHandlers() {
        Map<String, UnaryRequestHandler> handlers = new LinkedHashMap<>();
        handlers.put(HarborProtocol.typeToken(ServerCheckRequest.class),
                (payload, clientIp, connectionId) -> handleServerCheck(clientIp, connectionId));
        handlers.put(HarborProtocol.typeToken(InstanceRequest.class), this::handleInstanceRequest);
        handlers.put(HarborProtocol.typeToken(BatchInstanceRequest.class),
                this::handleBatchInstanceRequest);
        handlers.put(HarborProtocol.typeToken(SubscribeServiceRequest.class), this::handleSubscribe);
        handlers.put(HarborProtocol.typeToken(ServiceQueryRequest.class),
                (payload, clientIp, connectionId) -> handleServiceQuery(payload));
        handlers.put(HarborProtocol.typeToken(ServiceListRequest.class),
                (payload, clientIp, connectionId) -> handleServiceList(payload));
        handlers.put(HarborProtocol.typeToken(HealthCheckRequest.class),
                (payload, clientIp, connectionId) -> handleHealthCheck());
        handlers.put(HarborProtocol.typeToken(DistroSyncRequest.class),
                (payload, clientIp, connectionId) -> handleDistroSync(payload));
        handlers.put(HarborProtocol.typeToken(DistroVerifyRequest.class),
                (payload, clientIp, connectionId) -> handleDistroVerify(payload));
        handlers.put(HarborProtocol.typeToken(DistroSnapshotRequest.class),
                (payload, clientIp, connectionId) -> handleDistroSnapshot());
        // Config center is out of scope for a naming registry; answer silently
        // so that nacos-client does not keep retrying the listen.
        handlers.put(HarborProtocol.CONFIG_LISTEN_REQUEST,
                (payload, clientIp, connectionId) ->
                        HarborProtocol.encodeResponse(ConfigBatchListenResponse.ok()));
        return handlers;
    }

    /**
     * Handles all unary {@code Request.request(Payload)} calls.
     * Dispatches based on {@code Payload.metadata.type} to the appropriate
     * naming handler.
     */
    private class RequestHandler implements WireMethodHandler {

        @Override
        public Message handle(Message request, WireCallContext context) {
            Payload payload = (Payload) request;
            String type = payload.getMetadata().getType();
            String clientIp = payload.getMetadata().getClientIp();

            // Resolve the connectionId from the wire call context (injected by
            // the wire layer from the parent channel attribute).  This is the
            // per-TCP-connection unique ID, safe even when multiple processes
            // from the same clientIp connect simultaneously.
            String connectionId = context.getAttachment(WireConstants.CONNECTION_ID);

            // Refresh the specific connection's active time. connectionId is minted per
            // TCP connection at setup (#5) and propagated via WireCallContext, so this is
            // always the precise path; the old clientIp fallback has been removed.
            connectionManager.refreshActiveTime(connectionId);

            UnaryRequestHandler handler = unaryHandlers.get(type);
            if (handler == null) {
                log.warn("[harbor] unknown request type: {}", type);
                return HarborProtocol.encodeError(type, "Unknown request type: " + type);
            }
            try {
                return handler.handle(payload, clientIp, connectionId);
            } catch (Exception e) {
                log.error("[harbor] error handling request type={}", type, e);
                return HarborProtocol.encodeError(type, e.getMessage());
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
        public StreamSource<Message> handleBidiStream(StreamSource<Message> requestStream,
                                                      WireCallContext context) {
            // Create a push subject for server→client notifications
            StreamSubject<Message> pushSubject = new StreamSubject<>();

            // Resolve the connectionId from the parent channel attribute
            // (propagated via WireCallContext by the wire layer).
            String initialConnectionId = context.getAttachment(WireConstants.CONNECTION_ID);

            // Process incoming client messages (ConnectionSetupRequest, acks, etc.)
            requestStream.subscribe(new StreamObserver<>() {
                private final String connectionId = initialConnectionId;

                @Override
                public void onNext(Message item) {
                    if (!(item instanceof Payload payload)) {
                        return;
                    }
                    String type = payload.getMetadata().getType();
                    String ip = payload.getMetadata().getClientIp();

                    // Liveness = a message arrived on this connection. Record it
                    // before dispatch, so a handler that throws can't silently drop
                    // the beat (lastActiveTime is the sole health/watchdog clock).
                    connectionManager.refreshActiveTime(connectionId);

                    if (HarborProtocol.typeToken(ConnectionSetupRequest.class).equals(type)) {
                        ConnectionSetupRequest setup =
                                HarborProtocol.parseBody(payload, ConnectionSetupRequest.class);
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
                        Payload setupAck = HarborProtocol.encodePush(new SetupAckRequest(Map.of()));
                        log.debug("[harbor] sending SetupAckRequest to connId={}, clientIp={}",
                                connectionId, ip);
                        pushSubject.onNext(setupAck);
                    } else if (HarborProtocol.typeToken(NotifySubscriberResponse.class).equals(type)) {
                        // Client ack for a NotifySubscriberRequest — no action needed
                        log.debug("[harbor] received NotifySubscriberResponse ack");
                    } else {
                        log.debug("[harbor] bi-stream received type={}", type);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.info("[harbor] bi-stream error: {}", throwable.getMessage());
                    // The notification stream died → run the full closure transaction now.
                    // This is the EARLIEST signal (the RST/GOAWAY frame precedes the TCP FIN),
                    // and — critically — the ONLY one when the client resets just this stream
                    // while keeping the TCP connection up: unary HealthCheckRequests then keep
                    // refreshing lastActiveTime, so neither channelInactive nor the 90s watchdog
                    // fires, and the session would otherwise linger as "healthy" behind a dead
                    // push channel.  Idempotent, so the channelInactive that usually follows is
                    // a no-op.
                    connectionCleanup.cleanup(connectionId);
                    // Do NOT call pushSubject.onCompleted() here.
                    // The client already sent RST/GOAWAY — the stream is dead.
                    // Sending trailers on a reset stream confuses the Nacos
                    // client SDK and triggers an immediate reconnect cycle.
                    // (ConnectionCleanup's connectionManager.remove completes
                    // the push subject as part of the closure.)
                }

                @Override
                public void onCompleted() {
                    log.info("[harbor] bi-stream completed for connId={}", connectionId);
                    // Client END_STREAM on this stream is the same stream-only-death case as
                    // onError — the TCP connection may well stay up — so this is what tears the
                    // session down; idempotent against a later channelInactive.
                    connectionCleanup.cleanup(connectionId);
                    // Same as onError: the client initiated the close; the closure
                    // transaction owns push-subject completion.
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
        ServerCheckResponse response = new ServerCheckResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setConnectionId(connectionId);
        response.setSupportAbilityNegotiation(false);
        return HarborProtocol.encodeResponse(response, clientIp);
    }

    private Payload handleInstanceRequest(Payload payload, String clientIp, String connectionId) {
        InstanceRequest request = HarborProtocol.parseBody(payload, InstanceRequest.class);
        String namespace = request.getNamespace();
        String groupName = request.getGroupName();
        String serviceName = request.getServiceName();
        String type = request.getType();

        Instance instance = request.getInstance();
        if (instance == null) {
            return instanceError("Missing instance");
        }

        // Set default instanceId if not provided
        if (instance.getInstanceId() == null || instance.getInstanceId().isEmpty()) {
            String groupedName = groupName + "@@" + serviceName;
            instance.setInstanceId(instance.getIp() + "#" + instance.getPort() + "#" + groupedName);
        }

        if (HarborProtocol.REGISTER_INSTANCE.equals(type)) {
            serviceStorage.registerInstance(namespace, groupName, serviceName, instance, connectionId);
            // Sync full client state to peers (client-level granularity)
            syncClientDataToPeers(connectionId);
        } else if (HarborProtocol.DEREGISTER_INSTANCE.equals(type)) {
            serviceStorage.deregisterInstance(namespace, groupName, serviceName, instance, connectionId);
            // Deregister is also a CHANGE (full client state replacement), not DELETE.
            // DELETE is only used when the entire connection goes away.
            syncClientDataToPeers(connectionId);
        } else {
            return instanceError("Unknown instance operation type: " + type);
        }

        InstanceResponse response = new InstanceResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setType(type);
        return HarborProtocol.encodeResponse(response, clientIp);
    }

    /**
     * Instance replies are shared by the single and batch paths, but the token
     * must name the class actually being sent, so failures go out under the
     * reply type the caller planned for.
     */
    private Payload instanceError(String message) {
        return HarborProtocol.encodeError(
                HarborProtocol.typeToken(InstanceResponse.class), message);
    }

    private Payload handleBatchInstanceRequest(Payload payload, String clientIp, String connectionId) {
        BatchInstanceRequest request = HarborProtocol.parseBody(payload, BatchInstanceRequest.class);
        if (!HarborProtocol.BATCH_REGISTER_INSTANCE.equals(request.getType())) {
            return HarborProtocol.encodeError(
                    HarborProtocol.typeToken(BatchInstanceResponse.class),
                    "Unsupported request type: " + request.getType());
        }

        String namespace = request.getNamespace();
        String groupName = request.getGroupName();
        String serviceName = request.getServiceName();

        List<Instance> instances = request.getInstances();
        if (instances == null || instances.isEmpty()) {
            return HarborProtocol.encodeError(
                    HarborProtocol.typeToken(BatchInstanceResponse.class), "Missing instances");
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

        BatchInstanceResponse response = new BatchInstanceResponse(request.getType());
        response.setResultCode(200);
        response.setSuccess(true);
        return HarborProtocol.encodeResponse(response, clientIp);
    }

    private Payload handleSubscribe(Payload payload, String clientIp, String connectionId) {
        SubscribeServiceRequest request =
                HarborProtocol.parseBody(payload, SubscribeServiceRequest.class);
        String namespace = request.getNamespace();
        String groupName = request.getGroupName();
        String serviceName = request.getServiceName();
        boolean subscribe = request.isSubscribe();
        String clusters = request.getClusters();

        if (subscribe) {
            serviceStorage.addSubscriber(namespace, groupName, serviceName, connectionId,
                    clusters);
        } else {
            serviceStorage.removeSubscriber(namespace, groupName, serviceName, connectionId);
        }

        // Nacos answers a subscribe with cluster and enabled filtering but keeps
        // unhealthy instances visible, so the watcher learns the whole picture.
        ServiceInfo serviceInfo = ServiceInstanceSelector.select(
                serviceStorage.buildServiceInfo(namespace, groupName, serviceName),
                clusters, false, true);

        SubscribeServiceResponse response = new SubscribeServiceResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setServiceInfo(serviceInfo);
        return HarborProtocol.encodeResponse(response, clientIp);
    }

    private Payload handleServiceQuery(Payload payload) {
        ServiceQueryRequest request = HarborProtocol.parseBody(payload, ServiceQueryRequest.class);
        String namespace = request.getNamespace();
        String groupName = request.getGroupName();
        String serviceName = request.getServiceName();
        if (request.getUdpPort() != 0) {
            // Named rather than swallowed: UDP push is a v1 transport, and harbor
            // notifies over the bi-directional stream.
            log.warn("[harbor] ignoring ServiceQueryRequest.udpPort={} for {},"
                    + " notifications go over the bi-stream", request.getUdpPort(), serviceName);
        }

        ServiceInfo serviceInfo = ServiceInstanceSelector.select(
                serviceStorage.buildServiceInfo(namespace, groupName, serviceName),
                request.getCluster(), request.isHealthyOnly(), true);

        QueryServiceResponse response = new QueryServiceResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setServiceInfo(serviceInfo);
        return HarborProtocol.encodeResponse(response);
    }

    private Payload handleServiceList(Payload payload) {
        ServiceListRequest request = HarborProtocol.parseBody(payload, ServiceListRequest.class);
        String namespace = request.getNamespace();
        String groupName = request.getGroupName();
        if (groupName == null || groupName.isEmpty()) {
            groupName = "DEFAULT_GROUP";
        }

        List<String> services = serviceStorage.listServices(namespace, groupName);

        ServiceListResponse response = new ServiceListResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setCount(services.size());
        response.setServiceNames(services);
        return HarborProtocol.encodeResponse(response);
    }

    private Payload handleHealthCheck() {
        HealthCheckResponse response = new HealthCheckResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setStatus("SERVING");
        return HarborProtocol.encodeResponse(response);
    }

    // ========================================================================
    // Distro inter-node handlers
    // ========================================================================

    private Payload handleDistroSync(Payload payload) {
        DistroSyncRequest request = HarborProtocol.parseBody(payload, DistroSyncRequest.class);
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
        return HarborProtocol.encodeResponse(response);
    }

    private Payload handleDistroVerify(Payload payload) {
        DistroVerifyRequest request = HarborProtocol.parseBody(payload, DistroVerifyRequest.class);
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
            response.setMismatchedConnectionIds(mismatched);
        }
        return HarborProtocol.encodeResponse(response);
    }

    private Payload handleDistroSnapshot() {
        byte[] snapshot = distroProtocol.onSnapshot();

        DistroSnapshotResponse response = new DistroSnapshotResponse();
        response.setResultCode(200);
        response.setSuccess(true);
        response.setContent(snapshot != null
                ? Base64.getEncoder().encodeToString(snapshot) : "");
        return HarborProtocol.encodeResponse(response);
    }

    // ========================================================================
    // Listener hooks
    // ========================================================================

    private void syncClientDataToPeers(String connId) {
        // Coalesced, latest-state outbound sync: bursts on this client merge into one
        // push that re-reads the client's current full state at fire time.
        distroProtocol.requestSyncChange(connId);
    }

    private void onServiceChange(ServiceKey service) {
        // Register that this service changed. The PushDelayTaskEngine coalesces every
        // per-subscriber callback into ONE service-level task and, at fire time,
        // re-reads the CURRENT ServiceInfo and the live subscriber set. No per-connection
        // snapshot is cached: caching and resending a snapshot is exactly what risked
        // regressing a client to stale state when retries arrived out of order.
        pushEngine.requestPush(service);
    }
}
