package org.hongxi.jaws.wire;

import com.google.protobuf.Descriptors;
import io.netty.channel.ChannelPipeline;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.transport.MessageHandler;
import org.hongxi.jaws.transport.http2.AbstractHttp2Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
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

    private static final Logger log = LoggerFactory.getLogger(WireServer.class);

    private final WireHandlerRegistry registry;
    /**
     * Provider pipeline mode only: a registry holding just the built-in
     * protocol services (health), so both server modes serve them through the
     * same {@link WireMethodHandler} objects instead of a second
     * implementation. Null in Direct API mode, where the user registry already
     * holds them.
     */
    private final WireHandlerRegistry builtinRegistry;
    private final MessageHandler messageHandler;
    private final WireHealthService healthService;
    private final WireReflectionService reflectionService;

    /** Max size of a single inbound gRPC message in bytes. */
    private final int maxMessageSize;
    /** Max size of inbound HTTP/2 headers (metadata) in bytes. */
    private final int maxInboundMetadataSize;
    /** Encoding name configured for response compression, resolved per stream. */
    private final String configuredCompression;
    /**
     * Which encoding names are selectable for responses. Replaces the
     * previously hardcoded identity/gzip pair: registering a codec here is all
     * it takes to make it usable by name.
     */
    private volatile CompressorRegistry compressorRegistry = CompressorRegistry.getDefaultInstance();
    /** What this server can decompress, and what it advertises to clients. */
    private volatile DecompressorRegistry decompressorRegistry =
            DecompressorRegistry.getDefaultInstance();

    /**
     * Parent-channel attribute keys that should be propagated into every
     * call's {@link WireCallContext} (or Provider-pipeline request attachments).
     * <p>
     * The wire layer does not interpret these keys — it merely copies the
     * values from the parent (TCP) channel attribute into the per-call context
     * so that application handlers can access connection-level metadata
     * (e.g. a connectionId) without reaching into the Netty pipeline.
     */
    private final Set<String> connectionAttributeKeys = new LinkedHashSet<>();

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
        this.builtinRegistry = null;
        this.messageHandler = null;
        this.healthService = new WireHealthService();
        this.healthService.registerTo(registry);
        this.reflectionService = createHandlerModeReflectionService(registry);
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        this.maxInboundMetadataSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_METADATA_SIZE);
        this.configuredCompression = url.getParameter(UrlParam.Transport.COMPRESSION);
    }

    /**
     * Provider pipeline mode: use a Jaws {@link MessageHandler} pipeline, bridged
     * via {@link WireCallDispatcher.ProviderCallDispatcher}.
     * <p>
     * The standard {@code grpc.health.v1.Health} and
     * {@code grpc.reflection.v1.ServerReflection} services are
     * automatically available; health is served by the same handlers Direct
     * API mode registers, through a built-in registry of its own.
     */
    public WireServer(URL url, MessageHandler messageHandler) {
        super(url, "WireServer");
        this.registry = null;
        this.builtinRegistry = new WireHandlerRegistry();
        this.messageHandler = messageHandler;
        this.healthService = new WireHealthService();
        this.healthService.registerTo(builtinRegistry);
        this.reflectionService = createProviderModeReflectionService(url, builtinRegistry);
        this.maxMessageSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE);
        this.maxInboundMetadataSize = url.getIntParameter(UrlParam.Transport.MAX_INBOUND_METADATA_SIZE);
        this.configuredCompression = url.getParameter(UrlParam.Transport.COMPRESSION);
    }

    /**
     * Register a parent-channel attribute key for propagation into every
     * call context.  The wire layer will read
     * {@code parentChannel.attr(AttributeKey.valueOf(key))} and merge the
     * value into the per-call attachments.
     * <p>
     * <b>Must be called before {@link #open()}.</b>  The key set is read
     * without synchronization by Netty worker threads when a stream channel
     * is initialized; registrations made before {@code start()} become visible
     * to those threads via the thread-start happens-before edge.  Calling this
     * method after the server has started is not thread-safe and may leave
     * later registrations invisible (or worse) to in-flight stream setup.
     *
     * @param key the attribute key name (e.g. {@link WireConstants#CONNECTION_ID})
     */
    public void addConnectionAttributeKey(String key) {
        connectionAttributeKeys.add(Objects.requireNonNull(key, "key"));
    }

    /**
     * @return an unmodifiable view of the configured connection attribute keys
     */
    Set<String> getConnectionAttributeKeys() {
        return Collections.unmodifiableSet(connectionAttributeKeys);
    }

    /**
     * @return the auto-registered health service, for managing per-service
     *         statuses (e.g. {@code setStatus("", ServingStatus.NOT_SERVING)}
     *         during graceful shutdown)
     */
    public WireHealthService getHealthService() {
        return healthService;
    }

    /**
     * @return the auto-installed reflection service, whose enumeration is what
     *         reflection-driven tools (grpcurl and friends) see as this
     *         server's catalog
     */
    WireReflectionService getReflectionService() {
        return reflectionService;
    }

    /**
     * Resolve the configured encoding name against the compressor registry.
     * Done per stream rather than once at construction so that a registry
     * configured after the server exists still counts.
     *
     * @return the compressor for responses, identity when nothing usable was
     *         configured
     */
    private Compressor resolveResponseCompressor() {
        if (configuredCompression == null || configuredCompression.isEmpty()
                || WireConstants.ENCODING_IDENTITY.equals(configuredCompression)) {
            return Codec.Identity.NONE;
        }
        Compressor compressor = compressorRegistry.lookupCompressor(configuredCompression);
        if (compressor == null) {
            // Naming an unregistered codec must not look like it took effect
            log.warn("No compressor registered for '{}', responding uncompressed",
                    configuredCompression);
            return Codec.Identity.NONE;
        }
        return compressor;
    }

    /**
     * Replace the compressors selectable by the {@code compression} parameter.
     * Mirrors grpc-java's {@code ServerBuilder.compressorRegistry(...)}.
     *
     * @param compressorRegistry the registry to use from now on
     */
    public void setCompressorRegistry(CompressorRegistry compressorRegistry) {
        this.compressorRegistry = compressorRegistry != null
                ? compressorRegistry : CompressorRegistry.getDefaultInstance();
    }

    /**
     * Replace what this server can decompress and advertises in
     * {@code grpc-accept-encoding}. Mirrors grpc-java's
     * {@code ServerBuilder.decompressorRegistry(...)}.
     *
     * @param decompressorRegistry the registry to use from now on
     */
    public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
        this.decompressorRegistry = decompressorRegistry != null
                ? decompressorRegistry : DecompressorRegistry.getDefaultInstance();
    }

    /**
     * Creates the per-stream observer of inbound calls: message counts, byte
     * sizes and terminal statuses, i.e. the layer metrics are built on.
     * Configured programmatically rather than by URL because a tracer is code,
     * not a deployment knob.
     */
    private volatile ServerStreamTracer.Factory streamTracerFactory;

    /**
     * Observe every inbound stream. {@code null} leaves each stream on
     * {@link ServerStreamTracer#NOOP}, which costs nothing.
     *
     * @param streamTracerFactory the factory, or {@code null} to stop observing
     */
    public void setStreamTracerFactory(ServerStreamTracer.Factory streamTracerFactory) {
        this.streamTracerFactory = streamTracerFactory;
    }

    @Override
    protected void addOptionalChannelHandlers(ChannelPipeline pipeline) {
        // gRPC keepalive guard: permit client PINGs no faster than
        // permitPingIntervalMs (default 5min, same as grpc-java); faster PINGs
        // get GOAWAY too_many_pings. Set 0 to permit all.
        long permitMs = url.getLongParameter(UrlParam.Transport.PERMIT_PING_INTERVAL_MS);
        int permitStrikes = url.getIntParameter(UrlParam.Transport.PERMIT_PING_STRIKES);
        pipeline.addLast("wire_keepalive", new WireKeepaliveHandler(permitMs, permitStrikes));

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
            dispatcher = new WireCallDispatcher.HandlerCallDispatcher(
                    registry, connectionAttributeKeys);
        } else {
            dispatcher = new WireCallDispatcher.ProviderCallDispatcher(
                    messageHandler, builtinRegistry, connectionAttributeKeys);
        }
        streamChannel.pipeline().addLast(
                new WireStreamServerHandler(dispatcher, reflectionService,
                        serverExecutor, maxMessageSize, maxInboundMetadataSize,
                        resolveResponseCompressor(), decompressorRegistry, streamTracerFactory));
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
     * in a shared static map keyed by host:port, and the built-in protocol
     * services live in {@code builtinRegistry}. The reflection service reads
     * from both lazily on every request.
     * <p>
     * Names are derived from the descriptor set alone, so advertising a
     * built-in is a matter of contributing its descriptors — the same rule that
     * makes business services visible, and the reason a server that answers
     * {@code Check} also lists {@code grpc.health.v1.Health}.
     */
    private static WireReflectionService createProviderModeReflectionService(
            URL url, WireHandlerRegistry builtinRegistry) {
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
            result.addAll(builtinRegistry.collectFileDescriptors());
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
