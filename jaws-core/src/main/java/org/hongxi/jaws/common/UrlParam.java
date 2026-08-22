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
        public static final Def<String> LOAD_BALANCE = new Def<>("loadBalance", "leastActive");
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
        public static final Def<String> CODEC = new Def<>("codec", "jaws");
        public static final Def<String> SERIALIZATION = new Def<>("serialization", "hessian2");
        public static final Def<String> TRANSPORT_FACTORY = new Def<>("transportFactory", "netty");
        public static final Def<String> PROXY = new Def<>("proxy", "jdk");
        public static final Def<String> FILTER = new Def<>("filter", "");
        public static final Def<Integer> REQUEST_TIMEOUT = new Def<>("requestTimeout", 1000);
        public static final Def<Integer> CONNECT_TIMEOUT = new Def<>("connectTimeout", 1000);
        public static final Def<Integer> MAX_CONTENT_LENGTH = new Def<>("maxContentLength", 10 * 1024 * 1024);
        public static final Def<Long> HEARTBEAT = new Def<>("heartbeat", 0L);
        public static final Def<Boolean> TRANSFER_EXCEPTION_STACK = new Def<>("transferExceptionStack", true);

        private Transport() {
        }
    }

    // ---- Server ----

    public static final class Server {
        public static final Def<String> HOST = new Def<>("host", "");
        public static final Def<Integer> PORT = new Def<>("port", 0);
        public static final Def<Integer> MAX_CONNECTIONS = new Def<>("maxServerConnections", 100000);
        public static final Def<Integer> MIN_WORKER_THREADS = new Def<>("minWorkerThreads", 20);
        public static final Def<Integer> MAX_WORKER_THREADS = new Def<>("maxWorkerThreads", 200);
        public static final Def<Integer> WORKER_QUEUE_SIZE = new Def<>("workerQueueSize", 0);
        public static final Def<Boolean> ACCESS_LOG = new Def<>("accessLog", false);
        public static final Def<Integer> GRACEFUL_SHUTDOWN_TIMEOUT = new Def<>("gracefulShutdownTimeout", 10000);

        private Server() {
        }
    }

    // ---- Client ----

    public static final class Client {
        public static final Def<Boolean> CHECK = new Def<>("check", true);
        public static final Def<Boolean> THROW_EXCEPTION = new Def<>("throwException", true);
        public static final Def<Integer> FUSING_THRESHOLD = new Def<>("fusingThreshold", 10);
        public static final Def<String> DIRECT_URL = new Def<>("directUrl", "");

        private Client() {
        }
    }

    // ---- Registry ----

    public static final class Registry {
        public static final Def<Long> RETRY_PERIOD = new Def<>("registryRetryPeriod", 30 * 1000L);
        public static final Def<Integer> SESSION_TIMEOUT = new Def<>("registrySessionTimeout", 60 * 1000);
        public static final Def<Integer> FAILBACK_PERIOD = new Def<>("failbackPeriod", 5000);

        private Registry() {
        }
    }
}
