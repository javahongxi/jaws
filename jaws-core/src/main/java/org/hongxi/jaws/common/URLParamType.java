package org.hongxi.jaws.common;

/**
 * Created by shenhongxi on 2020/6/27.
 */
public enum URLParamType {

    // ==================== Server & Client Shared ====================

    version("version", "1.0"),

    requestTimeout("requestTimeout", 1000),

    maxContentLength("maxContentLength", 10 * 1024 * 1024),

    cluster("cluster", JawsConstants.DEFAULT_VALUE),
    loadBalance("loadBalance", "leastActive"),
    haStrategy("haStrategy", "failover"),
    protocol("protocol", JawsConstants.PROTOCOL_JAWS),
    path("path", ""),
    host("host", ""),
    port("port", 0),
    proxy("proxy", "jdk"),
    filter("filter", ""),

    heartbeatFactory("heartbeatFactory", "jaws"),

    serialization("serialization", "hessian2"),
    codec("codec", "jaws"),
    endpointFactory("endpointFactory", "netty"),

    group("group", "default_rpc"),

    nodeType("nodeType", JawsConstants.NODE_TYPE_SERVICE),

    application("application", JawsConstants.FRAMEWORK_NAME),
    module("module", JawsConstants.FRAMEWORK_NAME),

    directUrl("directUrl", ""),

    transExceptionStack("transExceptionStack", true),

    /** message processing dispatch strategy */
    providerProtectedStrategy("providerProtectedStrategy", "jaws"),

    /** interval in milliseconds between failback retry attempts */
    failbackPeriod("failbackPeriod", 5000),

    /** Warm-up duration in milliseconds. A newly started provider gradually increases its
     *  weight from 0 to full over this period to avoid cold-start overload. Default 10 minutes. */
    warmup("warmup", 10 * 60 * 1000),

    /** Provider startup timestamp in milliseconds. Set automatically during registration,
     *  used by consumer-side load balance to calculate warm-up weight. */
    timestamp("timestamp", 0L),

    /** Service auth token. Provider registers it to registry; consumer reads it and
     *  attaches to request. Provider validates the token on each invocation. */
    token("token", ""),

    // ==================== Server Only ====================

    maxServerConnections("maxServerConnections", 100000),

    minWorkerThreads("minWorkerThreads", 20),

    maxWorkerThreads("maxWorkerThreads", 200),

    workerQueueSize("workerQueueSize", 0),

    /** multi services share the same channel (port) */
    shareChannel("shareChannel", true),

    accessLog("accessLog", false),

    /** Graceful shutdown timeout in milliseconds. During this period, the server stops accepting
     *  new requests and waits for in-flight requests to complete before closing connections. */
    gracefulShutdownTimeout("gracefulShutdownTimeout", 10000),

    // ==================== Client Only ====================

    connectTimeout("connectTimeout", 1000),

    minClientConnections("minClientConnections", 2),

    registryRetryPeriod("registryRetryPeriod", 30 * 1000L),

    registrySessionTimeout("registrySessionTimeout", 60 * 1000),

    retries("retries", 0),

    check("check", true),

    throwException("throwException", true),

    fusingThreshold("fusingThreshold", 10);

    private final String name;
    private final String value;
    private int intValue;
    private long longValue;
    private boolean boolValue;

    URLParamType(String name, String value) {
        this.name = name;
        this.value = value;
    }

    URLParamType(String name, int intValue) {
        this.name = name;
        this.value = String.valueOf(intValue);
        this.intValue = intValue;
    }

    URLParamType(String name, long longValue) {
        this.name = name;
        this.value = String.valueOf(longValue);
        this.longValue = longValue;
    }

    URLParamType(String name, boolean boolValue) {
        this.name = name;
        this.value = String.valueOf(boolValue);
        this.boolValue = boolValue;
    }

    public String getName() {
        return name;
    }

    public String value() {
        return value;
    }

    public int intValue() {
        return intValue;
    }

    public long longValue() {
        return longValue;
    }

    public boolean boolValue() {
        return boolValue;
    }
}