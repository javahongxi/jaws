package org.hongxi.jaws.wire;

import com.google.protobuf.Descriptors;
import io.netty.channel.ChannelPipeline;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.http2.AbstractHttp2Server;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * gRPC server implementation based on Netty HTTP/2, independent of jaws-core's
 * {@code Http2Server} at the protocol layer. Implements the full gRPC wire format:
 * <ul>
 *   <li>5-byte length-prefixed message framing</li>
 *   <li>{@code /{service}/{method}} path routing</li>
 *   <li>Trailers-based status reporting (grpc-status / grpc-message)</li>
 *   <li>Keepalive PING policy: answers client PINGs (via Netty's auto-ACK) and
 *       guards against overly frequent PINGs with GOAWAY too_many_pings,
 *       per gRPC gRFC A8 {@code PERMIT_KEEPALIVE_TIME} semantics</li>
 * </ul>
 * <p>
 * Each inbound HTTP/2 stream is handled by {@link WireStreamServerHandler}
 * with a {@link WireCallDispatcher} strategy: direct API mode uses
 * {@link WireCallDispatcher.HandlerCallDispatcher}, Provider pipeline mode
 * uses {@link WireCallDispatcher.ProviderCallDispatcher}. The handler decodes
 * the protobuf request, dispatches to the registered handler on a business
 * thread pool, and writes the protobuf response as a gRPC frame.
 * <p>
 * The Netty bootstrap skeleton, business thread pool, GOAWAY-based graceful
 * shutdown, optional TLS with ALPN (h2 over TLS, interoperable with standard
 * gRPC clients), and connection limiting are provided by
 * {@link AbstractHttp2Server}.
 *
 * @author shenhongxi
 */
public class WireServer extends AbstractHttp2Server {

    private final WireHandlerRegistry registry;
    private final MessageHandler messageHandler;
    private final WireHealthService healthService;
    private final WireReflectionService reflectionService;

    /** Max size of a single inbound gRPC message in bytes. */
    private final int maxMessageSize;
    /** Max size of inbound HTTP/2 headers (metadata) in bytes. */
    private final int maxInboundMetadataSize;
    /** Configured response compression encoding (identity or gzip). */
    private final String compression;

    /** Scheduler for connection lifecycle checks (idle/age). */
    private static final ScheduledExecutorService LIFECYCLE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wire-lifecycle");
                t.setDaemon(true);
                return t;
            });

    /**
     * Direct API mode: use a {@link WireHandlerRegistry} for path-based routing
     * to typed {@link WireMethodHandler} instances.
     * <p>
     * The standard {@code grpc.health.v1.Health} and
     * {@code grpc.reflection.v1.ServerReflection} services are
     * automatically available; use {@link #getHealthService()} to manage
     * per-service statuses.
     */
    public WireServer(URL url, WireHandlerRegistry registry) {
        super(url, "WireServer");
        this.registry = registry;
        this.messageHandler = null;
        this.healthService = new WireHealthService();
        this.healthService.registerTo(registry);
        this.reflectionService = createHandlerModeReflectionService(registry);
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        this.maxInboundMetadataSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_METADATA_SIZE);
        this.compression = normalizeCompression(url);
    }

    /**
     * Provider pipeline mode: use a Jaws {@link MessageHandler} pipeline, bridged
     * via {@link WireCallDispatcher.ProviderCallDispatcher}.
     * <p>
     * The standard {@code grpc.health.v1.Health} and
     * {@code grpc.reflection.v1.ServerReflection} services are
     * automatically intercepted at the stream-handler level.
     */
    public WireServer(URL url, MessageHandler messageHandler) {
        super(url, "WireServer");
        this.registry = null;
        this.messageHandler = messageHandler;
        this.healthService = new WireHealthService();
        this.reflectionService = createProviderModeReflectionService(url);
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        this.maxInboundMetadataSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_METADATA_SIZE);
        this.compression = normalizeCompression(url);
    }

    /**
     * @return the auto-registered health service, for managing per-service
     *         statuses (e.g. {@code setStatus("", ServingStatus.NOT_SERVING)}
     *         during graceful shutdown)
     */
    public WireHealthService getHealthService() {
        return healthService;
    }

    private static String normalizeCompression(URL url) {
        String compression = url.getParameter(UrlParam.Transport.COMPRESSION);
        return compression != null && WireCompression.isSupported(compression)
                ? compression : WireConstants.ENCODING_IDENTITY;
    }

    @Override
    protected void addOptionalChannelHandlers(ChannelPipeline pipeline) {
        // gRPC keepalive guard: permit client PINGs no faster than
        // permitPingIntervalMs (default 5min, same as grpc-java); faster PINGs
        // get GOAWAY too_many_pings. Set 0 to permit all.
        long permitMs = url.getLongParameter(UrlParam.Transport.PERMIT_PING_INTERVAL_MS);
        pipeline.addLast("wire_keepalive", new WireKeepaliveHandler(permitMs));

        // Connection lifecycle: max idle / max age
        long maxIdle = url.getLongParameter(UrlParam.Server.MAX_CONNECTION_IDLE_MS);
        long maxAge = url.getLongParameter(UrlParam.Server.MAX_CONNECTION_AGE_MS);
        long grace = url.getLongParameter(UrlParam.Server.MAX_CONNECTION_AGE_GRACE_MS);
        if (maxIdle > 0 || maxAge > 0) {
            pipeline.addLast("wire_lifecycle", new WireConnectionLifecycleHandler(
                    maxIdle, maxAge, grace, LIFECYCLE_SCHEDULER));
        }
    }

    @Override
    protected void initStreamChannel(io.netty.channel.Channel streamChannel) {
        WireCallDispatcher dispatcher;
        if (registry != null) {
            dispatcher = new WireCallDispatcher.HandlerCallDispatcher(registry);
        } else {
            dispatcher = new WireCallDispatcher.ProviderCallDispatcher(messageHandler, healthService);
        }
        streamChannel.pipeline().addLast(
                new WireStreamServerHandler(dispatcher, reflectionService,
                        serverExecutor, maxMessageSize, maxInboundMetadataSize, compression));
    }

    // ========================================================================
    // Reflection service creation
    // ========================================================================

    /**
     * Handler mode: the registry already knows all services and their
     * handlers' protobuf types. The reflection service reads from the
     * registry lazily on every request.
     */
    private static WireReflectionService createHandlerModeReflectionService(
            WireHandlerRegistry registry) {
        return new WireReflectionService(
                registry::listServiceNames,
                registry::collectFileDescriptors);
    }

    /**
     * Provider mode: service interfaces are registered by {@link WireExporter}
     * in a shared static map keyed by host:port. The reflection service reads
     * from this map lazily on every request.
     */
    private static WireReflectionService createProviderModeReflectionService(URL url) {
        String hostPort = url.getHostPort();
        // Share the File descriptor set between the two suppliers so that
        // the service names are always consistent with the descriptors
        Supplier<Set<Descriptors.FileDescriptor>> fileDescriptors = () -> {
            Set<Descriptors.FileDescriptor> result = new LinkedHashSet<>();
            Set<Class<?>> interfaces = PROVIDER_SERVICE_INTERFACES.getOrDefault(
                    hostPort, Set.of());
            for (Class<?> iface : interfaces) {
                WireProtoTypes protoTypes = WireProtoTypes.fromServiceInterface(iface);
                result.addAll(protoTypes.getFileDescriptors());
            }
            return result;
        };
        // Derive service names from the proto FileDescriptors (e.g.
        // "greeter.Greeter") rather than from Java interface names so that
        // grpcurl can resolve them via file_containing_symbol.
        Supplier<Set<String>> serviceNames = () -> {
            Set<String> names = new LinkedHashSet<>();
            for (Descriptors.FileDescriptor fd : fileDescriptors.get()) {
                for (Descriptors.ServiceDescriptor sd : fd.getServices()) {
                    names.add(sd.getFullName());
                }
            }
            return names;
        };
        return new WireReflectionService(serviceNames, fileDescriptors);
    }

    // ========================================================================
    // Static registry for Provider mode reflection
    // ========================================================================

    /**
     * Shared registry of service interfaces per host:port, populated by
     * {@link WireExporter} and consumed by the reflection service in
     * Provider pipeline mode.
     */
    static final ConcurrentMap<String, Set<Class<?>>> PROVIDER_SERVICE_INTERFACES =
            new ConcurrentHashMap<>();

    /**
     * Register a service interface for reflection in Provider mode.
     * Called by {@link WireExporter} during construction.
     */
    static void addProviderServiceInterface(String hostPort, Class<?> serviceInterface) {
        PROVIDER_SERVICE_INTERFACES
                .computeIfAbsent(hostPort, k -> ConcurrentHashMap.newKeySet())
                .add(serviceInterface);
    }

    /**
     * Remove a service interface from the reflection registry.
     * Called by {@link WireExporter} during destruction.
     */
    static void removeProviderServiceInterface(String hostPort, Class<?> serviceInterface) {
        Set<Class<?>> interfaces = PROVIDER_SERVICE_INTERFACES.get(hostPort);
        if (interfaces != null) {
            interfaces.remove(serviceInterface);
            if (interfaces.isEmpty()) {
                PROVIDER_SERVICE_INTERFACES.remove(hostPort);
            }
        }
    }
}
