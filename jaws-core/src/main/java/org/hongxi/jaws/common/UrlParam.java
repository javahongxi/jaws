package org.hongxi.jaws.common;

/**
 * Centralized URL parameter key definitions with typed defaults.
 * <p>
 * Replaces the former {@code URLParamType} enum with a final class organized
 * into semantic inner classes so that each parameter is easy to locate by domain.
 *
 * @see org.hongxi.jaws.rpc.URL
 */
public final class UrlParam {

    private UrlParam() {
    }

    // ---- Parameter definition with typed default ----

    /**
     * A named parameter definition carrying a typed default value.
     *
     * @param <T> the default value type (String, Integer, Long, or Boolean)
     */
    public static final class Def<T> {
        private final String name;
        private final T defaultValue;

        public Def(String name, T defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue;
        }

        public String getName() {
            return name;
        }

        public T getDefaultValue() {
            return defaultValue;
        }

        public String value() {
            return String.valueOf(defaultValue);
        }

        public int intValue() {
            return (Integer) defaultValue;
        }

        public long longValue() {
            return (Long) defaultValue;
        }

        public boolean boolValue() {
            return (Boolean) defaultValue;
        }
    }

    // ---- Service Identity & Metadata ----

    public static final class Identity {
        public static final Def<String> VERSION = new Def<>("version", "1.0");
        public static final Def<String> GROUP = new Def<>("group", "default_rpc");
        public static final Def<String> PATH = new Def<>("path", "");
        public static final Def<String> ENDPOINT_TYPE = new Def<>("endpointType", JawsConstants.ENDPOINT_TYPE_SERVICE);
        public static final Def<String> APPLICATION = new Def<>("application", JawsConstants.FRAMEWORK_NAME);
        public static final Def<String> MODULE = new Def<>("module", JawsConstants.FRAMEWORK_NAME);
        public static final Def<String> TOKEN = new Def<>("token", "");
        public static final Def<String> TAG = new Def<>("tag", "");

        private Identity() {
        }
    }

    // ---- Cluster / Routing ----

    public static final class Cluster {
        public static final Def<String> LOAD_BALANCE = new Def<>("loadBalance", "adaptive");
        public static final Def<String> RETRY_POLICY = new Def<>("retryPolicy", "failover");
        public static final Def<Integer> RETRIES = new Def<>("retries", 0);
        public static final Def<Integer> WARMUP = new Def<>("warmup", 10 * 60 * 1000);
        public static final Def<Long> TIMESTAMP = new Def<>("timestamp", 0L);

        private Cluster() {
        }
    }

    // ---- Transport ----

    public static final class Transport {
        public static final Def<String> PROTOCOL = new Def<>("protocol", JawsConstants.PROTOCOL_JAWS);
        public static final Def<String> SERIALIZATION = new Def<>("serialization", "hessian2");
        public static final Def<String> TRANSPORT_FACTORY = new Def<>("transportFactory", "netty");
        public static final Def<String> PROXY = new Def<>("proxy", "jdk");
        public static final Def<String> FILTER = new Def<>("filter", "");
        public static final Def<Integer> REQUEST_TIMEOUT = new Def<>("requestTimeout", 1000);
        public static final Def<Integer> CONNECT_TIMEOUT = new Def<>("connectTimeout", 1000);
        public static final Def<Integer> MAX_CONTENT_LENGTH = new Def<>("maxContentLength", 10 * 1024 * 1024);
        public static final Def<Long> HEARTBEAT = new Def<>("heartbeat", 0L);
        public static final Def<Boolean> TRANSFER_EXCEPTION_STACK = new Def<>("transferExceptionStack", true);

        // TLS
        public static final Def<String> SSL_CERT_CHAIN = new Def<>("sslCertChain", "");
        public static final Def<String> SSL_PRIVATE_KEY = new Def<>("sslPrivateKey", "");
        public static final Def<String> SSL_TRUST_CERT = new Def<>("sslTrustCert", "");

        // Multi-connection (client side)
        public static final Def<Integer> CONNECTIONS = new Def<>("connections", 1);

        /**
         * gRPC keepalive policy (wire transport, server side): minimum permitted
         * interval between client PING frames. PINGs arriving faster trigger
         * GOAWAY too_many_pings (gRPC gRFC A8 semantics, same default as
         * grpc-java). 0 disables the guard.
         */
        public static final Def<Long> PERMIT_PING_INTERVAL_MS = new Def<>("permitPingIntervalMs", 300_000L);

        /**
         * Maximum size of a single inbound gRPC message in bytes (wire
         * transport, both client and server). Oversized messages are rejected
         * with RESOURCE_EXHAUSTED, same default as grpc-java.
         */
        public static final Def<Integer> MAX_INBOUND_MESSAGE_SIZE =
                new Def<>("maxInboundMessageSize", 4 * 1024 * 1024);

        /**
         * gRPC message compression encoding for outbound messages (wire
         * transport, client side): {@code identity} (no compression) or
         * {@code gzip}. Inbound compressed messages are always accepted.
         */
        public static final Def<String> COMPRESSION = new Def<>("compression", "identity");

        /**
         * gRPC client-side keepalive: interval between PING probes (ms).
         * 0 disables client keepalive (default). Mirrors grpc-java's
         * {@code keepAliveTime}.
         */
        public static final Def<Long> KEEPALIVE_TIME_MS = new Def<>("keepaliveTimeMs", 0L);

        /**
         * gRPC client-side keepalive: timeout waiting for PING ACK (ms).
         * If no ACK arrives within this window the connection is considered
         * dead and closed. Mirrors grpc-java's {@code keepAliveTimeout}.
         */
        public static final Def<Long> KEEPALIVE_TIMEOUT_MS = new Def<>("keepaliveTimeoutMs", 20_000L);

        /**
         * Maximum size of inbound HTTP/2 headers (bytes). Headers exceeding
         * this limit cause the stream to fail with REFUSED_STREAM.
         * Default 8KB, same order of magnitude as grpc-java.
         */
        public static final Def<Integer> MAX_INBOUND_METADATA_SIZE =
                new Def<>("maxInboundMetadataSize", 8 * 1024);

        /**
         * gRPC client retry: maximum number of attempts (1 = no retry, just
         * the initial call). Mirrors grpc-java's {@code maxAttempts}.
         */
        public static final Def<Integer> RETRY_MAX_ATTEMPTS = new Def<>("retryMaxAttempts", 2);

        /**
         * gRPC client retry: initial backoff before the first retry (ms).
         * Subsequent retries use exponential backoff multiplied by
         * {@link #RETRY_BACKOFF_MULTIPLIER_PCT}.
         */
        public static final Def<Long> RETRY_INITIAL_BACKOFF_MS = new Def<>("retryInitialBackoffMs", 100L);

        /**
         * gRPC client retry: upper bound on backoff delay (ms).
         */
        public static final Def<Long> RETRY_MAX_BACKOFF_MS = new Def<>("retryMaxBackoffMs", 1000L);

        /**
         * gRPC client retry: multiplier for exponential backoff, expressed
         * as a percentage (e.g. 160 = 1.6x). 100 = no growth.
         */
        public static final Def<Integer> RETRY_BACKOFF_MULTIPLIER_PCT = new Def<>("retryBackoffMultiplierPct", 200);

        /**
         * gRPC client retry: random jitter factor as a percentage of the
         * computed delay (e.g. 20 = ±20%). 0 disables jitter.
         */
        public static final Def<Integer> RETRY_JITTER_PCT = new Def<>("retryJitterPct", 20);

        /**
         * Whether to enable DNS-based service discovery for the wire client.
         * When enabled, the hostname is resolved to multiple A/AAAA records
         * and connections are established to each resolved address.
         */
        public static final Def<Boolean> DNS_ENABLED = new Def<>("dnsEnabled", false);

        /**
         * DNS service discovery: how often to re-resolve the hostname (ms).
         * 0 means resolve once at startup. Default 30s.
         */
        public static final Def<Long> DNS_REFRESH_INTERVAL_MS = new Def<>("dnsRefreshIntervalMs", 30_000L);

        private Transport() {
        }
    }

    // ---- Server ----

    public static final class Server {
        public static final Def<String> HOST = new Def<>("host", "");
        public static final Def<Integer> MAX_CONNECTIONS = new Def<>("maxServerConnections", 100000);
        public static final Def<Integer> MIN_WORKER_THREADS = new Def<>("minWorkerThreads", 20);
        public static final Def<Integer> MAX_WORKER_THREADS = new Def<>("maxWorkerThreads", 200);
        public static final Def<Integer> WORKER_QUEUE_SIZE = new Def<>("workerQueueSize", 0);
        public static final Def<Boolean> ACCESS_LOG = new Def<>("accessLog", false);
        public static final Def<Integer> GRACEFUL_SHUTDOWN_TIMEOUT = new Def<>("gracefulShutdownTimeout", 10000);

        /**
         * gRPC server: max time a connection may stay idle (no streams) before
         * the server sends GOAWAY and closes it (ms). 0 disables (default).
         */
        public static final Def<Long> MAX_CONNECTION_IDLE_MS = new Def<>("maxConnectionIdleMs", 0L);

        /**
         * gRPC server: max lifetime of a connection before graceful close (ms).
         * 0 disables (default). See grpc-java's {@code maxConnectionAge}.
         */
        public static final Def<Long> MAX_CONNECTION_AGE_MS = new Def<>("maxConnectionAgeMs", 0L);

        /**
         * gRPC server: grace period after sending GOAWAY for max-connection-age
         * before forcefully closing the connection (ms).
         */
        public static final Def<Long> MAX_CONNECTION_AGE_GRACE_MS = new Def<>("maxConnectionAgeGraceMs", 5000L);

        private Server() {
        }
    }

    // ---- Client ----

    public static final class Client {
        public static final Def<Boolean> CHECK = new Def<>("check", true);
        public static final Def<Boolean> THROW_EXCEPTION = new Def<>("throwException", true);
        public static final Def<Integer> FUSING_THRESHOLD = new Def<>("fusingThreshold", 10);

        /**
         * Whether to attempt reconnection in the request path when the
         * client channel is found to be disconnected.  Mirrors Dubbo's
         * {@code sendReconnect} parameter.
         */
        public static final Def<Boolean> SEND_RECONNECT = new Def<>("sendReconnect", true);

        private Client() {
        }
    }

    // ---- Registry ----

    public static final class Registry {
        public static final Def<Long> RETRY_PERIOD = new Def<>("registryRetryPeriod", 30 * 1000L);
        public static final Def<Integer> SESSION_TIMEOUT = new Def<>("registrySessionTimeout", 60 * 1000);
        public static final Def<Boolean> LOCAL_FILE_CACHE_ENABLED = new Def<>("cacheEnabled", true);
        public static final Def<String> CACHE_FILE = new Def<>("cacheFile", "");

        private Registry() {
        }
    }
}
