package org.hongxi.jaws.wire;

import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import org.hongxi.jaws.common.UrlParam;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.DefaultRequest;
import org.hongxi.jaws.rpc.Request;
import org.hongxi.jaws.rpc.Response;
import org.hongxi.jaws.rpc.URL;
import org.hongxi.jaws.stream.StreamSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A standalone gRPC channel that manages connections to a set of backend
 * addresses and load-balances calls across them, analogous to grpc-java's
 * {@code ManagedChannel}. This is the raw-client path: it is self-contained and
 * does NOT go through the Jaws Cluster pipeline ({@code ReferenceConfig} /
 * {@code ConsumerCoordinator} / {@code Registry}), which performs discovery via
 * Nacos / ZooKeeper / harbor instead.
 * <p>
 * The backend set is driven by a {@link NameResolver}: one {@link WireClient}
 * per resolved address, reconciled live. {@link DnsNameResolver} resolves a
 * DNS name (e.g. a Kubernetes headless Service) to its current pod IPs and
 * re-resolves to track scale in/out; {@link StaticNameResolver} serves a
 * fixed set (what {@link Builder#addAddress} produces). On every address update
 * the channel opens clients for new addresses and closes ones that disappeared,
 * so calls always balance across the live set.
 * <p>
 * Typical usage:
 * <pre>{@code
 * // static backends
 * try (ManagedChannel ch = ManagedChannel.builder()
 *         .addAddress("10.0.0.1:50051")
 *         .addAddress("10.0.0.2:50051")
 *         .roundRobin().build()) {
 *     Response r = ch.unaryCall("greeter.Greeter", "SayHello",
 *             req, HelloReply.parser());
 * }
 *
 * // DNS discovery (K8s headless Service), re-resolved every 30s
 * try (ManagedChannel ch = ManagedChannel.builder()
 *         .target("dns:///greeter.my-ns.svc.cluster.local:50051")
 *         .roundRobin().build()) { ... }
 *
 * // With client interceptors (mirrors grpc-java ManagedChannelBuilder.intercept)
 * try (ManagedChannel ch = ManagedChannel.builder()
 *         .addAddress("10.0.0.1:50051")
 *         .intercept(new AuthInterceptor())
 *         .intercept(new TracingInterceptor())
 *         .roundRobin().build()) { ... }
 * }</pre>
 * <p>
 * Supported policies: {@link LoadBalancePolicy#ROUND_ROBIN} (cycle, with
 * failover) and {@link LoadBalancePolicy#PICK_FIRST} (stick to the first
 * available, fall over in order).
 *
 * @author shenhongxi
 * @see WireClient
 * @see NameResolver
 */
public class ManagedChannel implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ManagedChannel.class);

    private final NameResolver resolver;
    private final LoadBalancePolicy policy;
    private final ClientConfig config;
    private final List<WireClientInterceptor> interceptors;

    /** Immutable snapshot of the live backends; replaced on each address sync. */
    private volatile List<WireClient> clients = List.of();

    /** Round-robin counter for ROUND_ROBIN policy. */
    private final AtomicInteger counter = new AtomicInteger(0);
    /** Current preferred index for PICK_FIRST policy. */
    private volatile int pickFirstIndex = 0;

    ManagedChannel(NameResolver resolver, LoadBalancePolicy policy, ClientConfig config,
                   List<WireClientInterceptor> interceptors) {
        this.resolver = resolver;
        this.policy = policy;
        this.config = config;
        this.interceptors = List.copyOf(interceptors);
        // start() delivers the initial address set synchronously (passthrough and
        // the first DNS resolve), then pushes updates on the resolver's schedule.
        resolver.start(new NameResolver.Listener() {
            @Override
            public void onAddresses(List<InetSocketAddress> addresses) {
                syncAddresses(addresses);
            }

            @Override
            public void onError(Throwable error) {
                log.warn("ManagedChannel name resolution error, keeping last known backends: {}",
                        error.getMessage());
            }
        });
        if (clients.isEmpty()) {
            resolver.shutdown();
            throw new JawsServiceException(
                    "ManagedChannel: no backend reachable for resolver " + resolver);
        }
        log.info("ManagedChannel created: {} backend(s), policy={}, timeout={}ms, interceptors={}",
                clients.size(), policy, config.requestTimeout, interceptors.size());
    }

    /**
     * Create a new {@link Builder} for configuring a {@link ManagedChannel}.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ========================================================================
    // Unary calls
    // ========================================================================

    public <Req extends Message, Resp extends Message> Response unaryCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser) {
        return unaryCall(serviceName, methodName, request, responseParser, null);
    }

    /**
     * Send a unary gRPC call with per-call metadata (gRPC custom headers),
     * load-balanced across the current backends.
     */
    public <Req extends Message, Resp extends Message> Response unaryCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser,
            Map<String, String> metadata) {
        return doUnaryCall(serviceName, methodName, request, responseParser, metadata);
    }

    // ========================================================================
    // Streaming calls
    // ========================================================================

    public <Req extends Message, Resp extends Message> StreamSource<Resp> streamingCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser) {
        return streamingCall(serviceName, methodName, request, responseParser, null);
    }

    /**
     * Send a server-streaming gRPC call with per-call metadata.
     */
    public <Req extends Message, Resp extends Message> StreamSource<Resp> streamingCall(
            String serviceName, String methodName,
            Req request, Parser<Resp> responseParser,
            Map<String, String> metadata) {
        //noinspection unchecked
        return (StreamSource<Resp>) (StreamSource<?>) doStreamingCall(
                serviceName, methodName, request, responseParser, metadata);
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * @return the number of backend addresses currently managed
     */
    public int size() {
        return clients.size();
    }

    /** Package-private view of the current backend clients, for tests. */
    List<WireClient> currentClients() {
        return clients;
    }

    /**
     * @return true if at least one backend client is available
     */
    public boolean isAvailable() {
        for (WireClient client : clients) {
            if (client.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        resolver.shutdown();
        for (WireClient client : clients) {
            closeQuietly(client);
        }
        clients = List.of();
        log.info("ManagedChannel closed");
    }

    // ========================================================================
    // Address reconciliation
    // ========================================================================

    /**
     * Reconcile the backend pool against a freshly resolved address set: open a
     * {@link WireClient} for each new address, keep the ones that persist, and
     * close the ones that disappeared. A backend that fails to connect is
     * skipped (best-effort) and retried on the next update.
     */
    private synchronized void syncAddresses(List<InetSocketAddress> addresses) {
        // Key by the NUMERIC endpoint, not the hostname: a resolved DNS name can
        // map to several IPs (e.g. localhost -> 127.0.0.1 and ::1) whose
        // InetSocketAddress.getHostString() all report the same hostname, which
        // would otherwise collapse them into a single backend.
        Map<String, InetSocketAddress> desired = new LinkedHashMap<>();
        for (InetSocketAddress a : addresses) {
            desired.putIfAbsent(endpointKey(a),
                    InetSocketAddress.createUnresolved(
                            a.getAddress() != null ? a.getAddress().getHostAddress() : a.getHostString(),
                            a.getPort()));
        }

        Map<String, WireClient> currentByKey = new HashMap<>();
        for (WireClient c : clients) {
            currentByKey.put(c.getUrl().getHost() + ":" + c.getUrl().getPort(), c);
        }
        // Close backends no longer in the resolved set
        for (Map.Entry<String, WireClient> e : currentByKey.entrySet()) {
            if (!desired.containsKey(e.getKey())) {
                log.info("ManagedChannel: backend {} removed, closing", e.getKey());
                closeQuietly(e.getValue());
            }
        }
        // Build the next snapshot: reuse persisting clients, open new ones
        List<WireClient> next = new ArrayList<>(desired.size());
        for (Map.Entry<String, InetSocketAddress> e : desired.entrySet()) {
            WireClient existing = currentByKey.get(e.getKey());
            if (existing != null) {
                next.add(existing);
                continue;
            }
            InetSocketAddress a = e.getValue();
            try {
                next.add(openClient(a.getHostString(), a.getPort()));
                log.info("ManagedChannel: backend {} added", e.getKey());
            } catch (Exception ex) {
                log.warn("ManagedChannel: backend {} unreachable, skipping: {}",
                        e.getKey(), ex.getMessage());
            }
        }
        this.clients = List.copyOf(next);
    }

    /**
     * Identity key for a backend endpoint. Uses the NUMERIC address when the
     * {@link InetSocketAddress} carries a resolved {@link java.net.InetAddress}
     * (so two IPs behind the same hostname — e.g. {@code localhost} → 127.0.0.1
     * and {@code ::1} — stay distinct), falling back to the host string for
     * unresolved (passthrough) endpoints.
     */
    static String endpointKey(InetSocketAddress a) {
        String host = a.getAddress() != null ? a.getAddress().getHostAddress() : a.getHostString();
        return host + ":" + a.getPort();
    }

    private WireClient openClient(String host, int port) {
        URL url = new URL("wire", host, port, "wire");
        url.addParameter(UrlParam.Transport.REQUEST_TIMEOUT.getName(),
                String.valueOf(config.requestTimeout));
        url.addParameter(UrlParam.Transport.CONNECT_TIMEOUT.getName(),
                String.valueOf(config.connectTimeout));
        url.addParameter(UrlParam.Transport.MAX_INBOUND_MESSAGE_SIZE.getName(),
                String.valueOf(config.maxInboundMessageSize));
        url.addParameter(UrlParam.Transport.COMPRESSION.getName(), config.compression);

        // Keepalive: the transport only installs the PING handler when timeMs > 0,
        // so writing a zero here is equivalent to leaving it unset.
        url.addParameter(UrlParam.Transport.KEEPALIVE_TIME_MS.getName(),
                String.valueOf(config.keepalive.timeMs()));
        url.addParameter(UrlParam.Transport.KEEPALIVE_TIMEOUT_MS.getName(),
                String.valueOf(config.keepalive.timeoutMs()));

        // Retry: maxAttempts <= 1 makes WireRetryPolicy.fromUrl return null.
        url.addParameter(UrlParam.Transport.RETRY_MAX_ATTEMPTS.getName(),
                String.valueOf(config.retry.maxAttempts()));
        url.addParameter(UrlParam.Transport.RETRY_INITIAL_BACKOFF_MS.getName(),
                String.valueOf(config.retry.initialBackoffMs()));
        url.addParameter(UrlParam.Transport.RETRY_MAX_BACKOFF_MS.getName(),
                String.valueOf(config.retry.maxBackoffMs()));
        url.addParameter(UrlParam.Transport.RETRY_BACKOFF_MULTIPLIER_PCT.getName(),
                String.valueOf(config.retry.backoffMultiplierPct()));
        url.addParameter(UrlParam.Transport.RETRY_JITTER_PCT.getName(),
                String.valueOf(config.retry.jitterPct()));

        // TLS: the transport enables TLS only when trustCert (one-way) or
        // certChain+privateKey (mutual) is a non-empty path; empty values keep h2c.
        url.addParameter(UrlParam.Transport.SSL_TRUST_CERT.getName(), config.tls.trustCert());
        url.addParameter(UrlParam.Transport.SSL_CERT_CHAIN.getName(), config.tls.certChain());
        url.addParameter(UrlParam.Transport.SSL_PRIVATE_KEY.getName(), config.tls.privateKey());

        WireClient client = new WireClient(url);
        for (WireClientInterceptor interceptor : interceptors) {
            client.addInterceptor(interceptor);
        }
        client.open();
        return client;
    }

    private static void closeQuietly(WireClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Failed to close WireClient: {}", client.getUrl(), e);
        }
    }

    // ========================================================================
    // Internal implementation
    // ========================================================================

    private Response doUnaryCall(String serviceName, String methodName,
                                Message request, Parser<? extends Message> responseParser,
                                Map<String, String> metadata) {
        Request jawsRequest = buildRequest(serviceName, methodName, request, metadata);
        List<WireClient> snapshot = clients;

        if (policy == LoadBalancePolicy.PICK_FIRST) {
            return doPickFirstUnary(jawsRequest, responseParser, snapshot);
        }

        // ROUND_ROBIN: try the selected client, fail over to others
        int n = snapshot.size();
        int startIdx = Math.abs(counter.getAndIncrement() % n);
        Exception lastException = null;

        for (int i = 0; i < n; i++) {
            WireClient client = snapshot.get((startIdx + i) % n);
            if (!client.isAvailable()) {
                continue;
            }
            try {
                return client.request(jawsRequest, responseParser);
            } catch (Exception e) {
                lastException = e;
                log.warn("ManagedChannel round-robin call failed: address={}:{}, error={}",
                        client.getUrl().getHost(), client.getUrl().getPort(), e.getMessage());
            }
        }

        throw new JawsServiceException(
                "ManagedChannel all " + n + " backend(s) failed for "
                        + serviceName + "/" + methodName, lastException);
    }

    private Response doPickFirstUnary(Request jawsRequest, Parser<? extends Message> responseParser,
                                      List<WireClient> snapshot) {
        int n = snapshot.size();
        for (int i = 0; i < n; i++) {
            int idx = (pickFirstIndex + i) % n;
            WireClient client = snapshot.get(idx);
            if (!client.isAvailable()) {
                continue;
            }
            try {
                Response response = client.request(jawsRequest, responseParser);
                pickFirstIndex = idx;   // success: prefer this client next time
                return response;
            } catch (Exception e) {
                log.warn("ManagedChannel pick-first call failed: address={}:{}, error={}",
                        client.getUrl().getHost(), client.getUrl().getPort(), e.getMessage());
            }
        }

        throw new JawsServiceException(
                "ManagedChannel all " + n + " backend(s) failed for "
                        + jawsRequest.getInterfaceName() + "/" + jawsRequest.getMethodName());
    }

    private StreamSource<Object> doStreamingCall(String serviceName, String methodName,
                                                    Message request, Parser<? extends Message> responseParser,
                                                    Map<String, String> metadata) {
        Request jawsRequest = buildRequest(serviceName, methodName, request, metadata);
        // For streaming, pick one client (no fail-over mid-stream)
        WireClient client = selectClient();
        return client.requestStream(jawsRequest, responseParser);
    }

    private WireClient selectClient() {
        List<WireClient> snapshot = clients;
        int n = snapshot.size();
        if (policy == LoadBalancePolicy.PICK_FIRST) {
            for (int i = 0; i < n; i++) {
                WireClient client = snapshot.get((pickFirstIndex + i) % n);
                if (client.isAvailable()) {
                    return client;
                }
            }
        } else {
            int idx = Math.abs(counter.getAndIncrement() % n);
            for (int i = 0; i < n; i++) {
                WireClient client = snapshot.get((idx + i) % n);
                if (client.isAvailable()) {
                    return client;
                }
            }
        }
        throw new JawsServiceException("ManagedChannel: no available backend address");
    }

    private Request buildRequest(String serviceName, String methodName,
                                 Message request, Map<String, String> metadata) {
        DefaultRequest jawsRequest = new DefaultRequest();
        jawsRequest.setInterfaceName(serviceName);
        jawsRequest.setMethodName(methodName);
        jawsRequest.setArguments(new Object[]{request});
        if (metadata != null && !metadata.isEmpty()) {
            for (var entry : metadata.entrySet()) {
                jawsRequest.setAttachment(entry.getKey(), entry.getValue());
            }
        }
        return jawsRequest;
    }

    // ========================================================================
    // Builder
    // ========================================================================

    /**
     * Builder for configuring and creating a {@link ManagedChannel}. Provide the
     * backends either as a static list ({@link #addAddress}) or via discovery
     * ({@link #target} / {@link #nameResolver}). At least one is required.
     */
    public static final class Builder {
        private final List<String> addresses = new ArrayList<>();
        private String target;
        private NameResolver nameResolver;
        private LoadBalancePolicy policy = LoadBalancePolicy.ROUND_ROBIN;
        private int requestTimeout = 5000;
        private int connectTimeout = 3000;
        private int maxInboundMessageSize = 4 * 1024 * 1024;
        private String compression = WireConstants.ENCODING_IDENTITY;
        private long dnsRefreshIntervalMs = 30_000L;
        private KeepaliveConfig keepalive = KeepaliveConfig.DISABLED;
        private RetryConfig retry = RetryConfig.DEFAULT;
        private String sslTrustCert = "";
        private String sslCertChain = "";
        private String sslPrivateKey = "";
        private final List<WireClientInterceptor> interceptors = new ArrayList<>();

        private Builder() {
        }

        /**
         * Add a backend gRPC server address in {@code host:port} format. Repeat
         * for multiple static backends (equivalent to a passthrough resolver).
         */
        public Builder addAddress(String address) {
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("address must not be blank");
            }
            addresses.add(address.trim());
            return this;
        }

        /**
         * Resolve backends from a target URI. Supported schemes:
         * <ul>
         *   <li>{@code dns:///host:port} (or a bare {@code host:port}) — DNS
         *       discovery, re-resolved every {@link #dnsRefreshIntervalMs}</li>
         *   <li>{@code passthrough:///host:port} — a single literal backend</li>
         * </ul>
         */
        public Builder target(String target) {
            this.target = target;
            return this;
        }

        /**
         * Use a caller-supplied {@link NameResolver} (e.g. a test double or a
         * custom discovery mechanism).
         */
        public Builder nameResolver(NameResolver nameResolver) {
            this.nameResolver = nameResolver;
            return this;
        }

        /** Set the load balance policy. Defaults to {@link LoadBalancePolicy#ROUND_ROBIN}. */
        public Builder loadBalancePolicy(LoadBalancePolicy policy) {
            this.policy = policy;
            return this;
        }

        /** Use round-robin load balancing. */
        public Builder roundRobin() {
            this.policy = LoadBalancePolicy.ROUND_ROBIN;
            return this;
        }

        /** Use pick-first load balancing. */
        public Builder pickFirst() {
            this.policy = LoadBalancePolicy.PICK_FIRST;
            return this;
        }

        /** Set the request timeout in milliseconds. Defaults to 5000ms. */
        public Builder requestTimeout(int timeoutMs) {
            this.requestTimeout = timeoutMs;
            return this;
        }

        /** Set the connect timeout in milliseconds. Defaults to 3000ms. */
        public Builder connectTimeout(int timeoutMs) {
            this.connectTimeout = timeoutMs;
            return this;
        }

        /** Set the maximum inbound message size in bytes. Defaults to 4MiB. */
        public Builder maxInboundMessageSize(int maxBytes) {
            this.maxInboundMessageSize = maxBytes;
            return this;
        }

        /** Set outbound compression ({@code "identity"} or {@code "gzip"}). Defaults to identity. */
        public Builder compression(String compression) {
            this.compression = compression;
            return this;
        }

        /** Set the DNS re-resolve interval in ms (used by {@code dns:///} targets). Defaults to 30s. */
        public Builder dnsRefreshIntervalMs(long ms) {
            this.dnsRefreshIntervalMs = ms;
            return this;
        }

        /**
         * Enable client keepalive: send an HTTP/2 PING every {@code timeMs} and
         * close the connection if no ACK arrives within {@code timeoutMs}.
         * Mirrors grpc-java's {@code keepAliveTime} / {@code keepAliveTimeout}.
         */
        public Builder keepAlive(long timeMs, long timeoutMs) {
            if (timeMs < 0 || timeoutMs < 0) {
                throw new IllegalArgumentException("keepalive durations must not be negative");
            }
            this.keepalive = new KeepaliveConfig(timeMs, timeoutMs);
            return this;
        }

        /** Disable client keepalive (the default). */
        public Builder keepAliveDisabled() {
            this.keepalive = KeepaliveConfig.DISABLED;
            return this;
        }

        /**
         * Configure gRPC client retry: up to {@code maxAttempts} total attempts
         * (1 disables retry), backing off exponentially from {@code initialMs}
         * capped at {@code maxMs}, grown by {@code multiplierPct} percent with
         * {@code jitterPct} percent random jitter. Defaults match the transport.
         */
        public Builder retry(int maxAttempts, long initialMs, long maxMs,
                             int multiplierPct, int jitterPct) {
            this.retry = new RetryConfig(maxAttempts, initialMs, maxMs, multiplierPct, jitterPct);
            return this;
        }

        /** Disable client retry (initial call only). */
        public Builder retryDisabled() {
            this.retry = new RetryConfig(1, 0L, 0L, 100, 0);
            return this;
        }

        /**
         * Enable one-way TLS: trust the server certificate anchored at
         * {@code trustCertPath} (PEM file). Calls use {@code https} scheme.
         */
        public Builder trustCert(String trustCertPath) {
            this.sslTrustCert = requireNonBlank(trustCertPath, "trustCertPath");
            return this;
        }

        /**
         * Enable mutual TLS in addition to {@link #trustCert}: present the client
         * certificate chain and private key (PEM files) during the handshake.
         */
        public Builder mutualTls(String certChainPath, String privateKeyPath) {
            this.sslCertChain = requireNonBlank(certChainPath, "certChainPath");
            this.sslPrivateKey = requireNonBlank(privateKeyPath, "privateKeyPath");
            return this;
        }

        private static String requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value.trim();
        }

        /**
         * Add a client interceptor applied to all calls through this channel.
         * Interceptors execute in registration order (first added = outermost).
         * <p>
         * Mirrors grpc-java's {@code ManagedChannelBuilder.intercept()}.
         *
         * @param interceptor the interceptor to add
         * @return this builder
         */
        public Builder intercept(WireClientInterceptor interceptor) {
            if (interceptor == null) {
                throw new IllegalArgumentException("interceptor must not be null");
            }
            interceptors.add(interceptor);
            return this;
        }

        /**
         * Build the {@link ManagedChannel}: select the resolver, start it, and
         * open a client per resolved address.
         *
         * @throws IllegalArgumentException if no backend source was provided
         * @throws JawsServiceException     if no backend is reachable at build time
         */
        public ManagedChannel build() {
            NameResolver resolver = resolveNameResolver();
            TlsConfig tls = new TlsConfig(sslCertChain, sslPrivateKey, sslTrustCert);
            return new ManagedChannel(resolver, policy,
                    new ClientConfig(requestTimeout, connectTimeout, maxInboundMessageSize,
                            compression, keepalive, retry, tls),
                    interceptors);
        }

        private NameResolver resolveNameResolver() {
            if (nameResolver != null) {
                return nameResolver;
            }
            if (target != null && !target.isBlank()) {
                return fromTarget(target.trim());
            }
            if (!addresses.isEmpty()) {
                List<InetSocketAddress> addrs = new ArrayList<>(addresses.size());
                for (String a : addresses) {
                    addrs.add(parseHostPort(a));
                }
                return new StaticNameResolver(addrs);
            }
            throw new IllegalArgumentException(
                    "ManagedChannel requires addAddress(...), target(...), or nameResolver(...)");
        }

        private NameResolver fromTarget(String t) {
            String scheme = "dns";
            String authority = t;
            int schemeIdx = t.indexOf("://");
            if (schemeIdx >= 0) {
                scheme = t.substring(0, schemeIdx);
                authority = t.substring(schemeIdx + 3);
                while (authority.startsWith("/")) {
                    authority = authority.substring(1);
                }
            }
            InetSocketAddress addr = parseHostPort(authority);
            if ("passthrough".equalsIgnoreCase(scheme)) {
                return new StaticNameResolver(List.of(addr));
            }
            if (!"dns".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("unsupported target scheme: " + scheme);
            }
            return new DnsNameResolver(addr.getHostString(), addr.getPort(), dnsRefreshIntervalMs);
        }
    }

    private static InetSocketAddress parseHostPort(String hostPort) {
        int idx = hostPort.lastIndexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException("address must be host:port but was: " + hostPort);
        }
        String host = hostPort.substring(0, idx).trim();
        int port = Integer.parseInt(hostPort.substring(idx + 1).trim());
        return InetSocketAddress.createUnresolved(host, port);
    }

    /** Immutable per-backend connection settings applied to every resolved address. */
    record ClientConfig(int requestTimeout, int connectTimeout,
                        int maxInboundMessageSize, String compression,
                        KeepaliveConfig keepalive, RetryConfig retry, TlsConfig tls) {
    }

    /** gRPC client keepalive (gRFC A8). {@code timeMs == 0} disables probing. */
    record KeepaliveConfig(long timeMs, long timeoutMs) {
        static final KeepaliveConfig DISABLED = new KeepaliveConfig(0L, 20_000L);
    }

    /** gRPC client retry (gRFC A6). {@code maxAttempts <= 1} disables retry. */
    record RetryConfig(int maxAttempts, long initialBackoffMs, long maxBackoffMs,
                       int backoffMultiplierPct, int jitterPct) {
        static final RetryConfig DEFAULT = new RetryConfig(2, 100L, 1000L, 200, 20);
    }

    /** TLS material as file paths. Empty {@code trustCert} disables TLS (h2c). */
    record TlsConfig(String certChain, String privateKey, String trustCert) {
        static final TlsConfig DISABLED = new TlsConfig("", "", "");
    }
}
